package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fakesni.FakeSniPreferences
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils

object CoreConfigManager {
    private var initConfigCache: String? = null
    private var initConfigCacheWithTun: String? = null

    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(false, guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) return buildV2rayCustomConfig(configContext)
            return toConfigResult(configContext, buildUnifiedConfig(configContext))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            return ConfigResult(false, guid, errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(false, guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) return buildV2rayCustomConfig(configContext)
            val v2rayConfig = buildUnifiedConfig(configContext)
            postProcessForSpeedtest(v2rayConfig)
            return toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            return ConfigResult(false, guid, errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildV2rayCustomConfig(configContext: CoreConfigContext): ConfigResult {
        val context = configContext.context
        val raw = MmkvManager.decodeServerRaw(configContext.guid)
            ?: return ConfigResult(false, configContext.guid, errorMessage = "Failed to build config context, config is empty")
        val result = ConfigResult(true, configContext.guid, raw)
        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return result
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            if (!json.has("stats")) json.add("stats", JsonObject())
            if (!json.has("policy")) {
                val policyObj = JsonObject()
                val systemObj = JsonObject()
                systemObj.addProperty("statsOutboundUplink", true)
                systemObj.addProperty("statsOutboundDownlink", true)
                policyObj.add("system", systemObj)
                json.add("policy", policyObj)
            }
        } else {
            json.remove("stats")
            json.remove("policy")
        }
        if (!needTun()) return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
        if (SettingsManager.canUseProcessRouting()) {
            val rulesJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            for (elem in rulesJson) {
                val rule = elem.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val process = rule.get("process")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val packages = process.mapNotNull { it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }.takeIf { it.isNotEmpty() } ?: continue
                val uids = PackageUidResolver.packageNamesToUids(context, packages).takeIf { it.isNotEmpty() } ?: continue
                rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
            }
        }
        val inboundsJson = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { json.add("inbounds", it) }
        val tunNotExists = inboundsJson.none { elem -> elem.isJsonObject && elem.asJsonObject.get("protocol")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString == "tun" }
        if (tunNotExists) {
            val templateConfig = initV2rayConfig(configContext)
            templateConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { inboundTun ->
                inboundTun.settings?.mtu = SettingsManager.getVpnMtu()
                inboundsJson.add(JsonUtil.parseString(JsonUtil.toJson(inboundTun)))
            }
        }
        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
    }

    private fun buildUnifiedConfig(configContext: CoreConfigContext): V2rayConfig {
        require(configContext.resolvedOutbounds.isNotEmpty())
        val primaryResolvedOutbound = configContext.resolvedOutbounds.first()
        val v2rayConfig = initV2rayConfig(configContext)
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.remarks = primaryResolvedOutbound.profile.remarks
        configureInbounds(v2rayConfig)
        if (v2rayConfig.outbounds.isNotEmpty()) v2rayConfig.outbounds.removeAt(0)
        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        val policyGroupBalancerTags = mutableMapOf<String, String>()
        val balancerStrategies = mutableListOf<BalancerStrategy>()
        configContext.resolvedOutbounds.forEachIndexed { index, spec ->
            buildOutbounds(index == 0, spec, existingTags, v2rayConfig, policyGroupBalancerTags, balancerStrategies)
        }
        configureRouting(configContext, v2rayConfig, policyGroupBalancerTags)
        configureFakeDns(v2rayConfig)
        configureDns(configContext, v2rayConfig, policyGroupBalancerTags)
        configureLocalDns(configContext, v2rayConfig)
        configureRootModeDns(v2rayConfig)
        if (primaryResolvedOutbound.resolvedType == CoreResolvedType.POLICYGROUP) {
            if (v2rayConfig.routing.domainStrategy == "IPIfNonMatch") {
                v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(ip = arrayListOf("0.0.0.0/0", "::/0"), balancerTag = AppConfig.TAG_BALANCER))
            } else {
                v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(network = "tcp,udp", balancerTag = AppConfig.TAG_BALANCER))
            }
        }
        applyObservability(v2rayConfig, balancerStrategies)
        applySpeedDisabled(v2rayConfig)
        resolveOutboundDomainsToHosts(v2rayConfig)
        return v2rayConfig
    }

    private fun buildOutbounds(
        prepend: Boolean,
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        if (resolvedOutbound.tag in existingTags) return
        when (resolvedOutbound.resolvedType) {
            CoreResolvedType.NORMAL -> handleNormalResolvedOutbound(resolvedOutbound, prepend, existingTags, v2rayConfig)
            CoreResolvedType.PROXYCHAIN -> handleProxyChainResolvedOutbound(resolvedOutbound, prepend, existingTags, v2rayConfig)
            CoreResolvedType.POLICYGROUP -> handlePolicyGroupResolvedOutbound(resolvedOutbound, prepend, existingTags, v2rayConfig, policyGroupBalancerTags, balancerStrategies)
        }
    }

    private fun handleNormalResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val profile = resolvedOutbound.resolvedProfiles.firstOrNull() ?: return
        val outbound = convertProfile2Outbound(profile) ?: return
        applyFakeSniIfEnabled(outbound, profile)
        outbound.tag = resolvedOutbound.tag
        if (prepend) v2rayConfig.outbounds.add(0, outbound) else v2rayConfig.outbounds.add(outbound)
        existingTags.add(resolvedOutbound.tag)
    }

    /**
     * When FakeSNI is enabled, Xray itself connects to the local FakeSNI listener.
     * TLS settings (especially the original serverName) remain untouched, so FakeSNI
     * receives the original ClientHello and can replace its SNI. No iptables interception
     * is needed and there is no feedback/restart loop.
     */
    private fun applyFakeSniIfEnabled(outbound: V2rayConfig.OutboundBean, profile: ProfileItem) {
        val prefs = FakeSniPreferences(profile.contextForFakeSni())
        if (!prefs.enabled) return
        if (profile.configType == EConfigType.WIREGUARD || profile.security?.lowercase() != "tls") return
        val settings = outbound.settings ?: return
        if (settings.address == null || settings.port == null) return
        settings.address = AppConfig.LOOPBACK
        settings.port = prefs.listenPort
        outbound.streamSettings?.sockopt?.let { sockopt ->
            // Prevent an inherited dialer chain from bypassing the local FakeSNI listener.
            sockopt.dialerProxy = null
        }
        LogUtil.i(AppConfig.TAG, "FakeSNI integrated: ${profile.remarks} -> ${AppConfig.LOOPBACK}:${prefs.listenPort}, Connect ${prefs.connectIp}:${prefs.connectPort}")
    }

    private fun handleProxyChainResolvedOutbound(resolvedOutbound: CoreConfigContext.ResolvedOutbound, prepend: Boolean, existingTags: MutableSet<String>, v2rayConfig: V2rayConfig) {
        val chainOutbounds = resolvedOutbound.resolvedProfiles.mapNotNull { profile -> convertProfile2Outbound(profile)?.also { applyFakeSniIfEnabled(it, profile) } }.toMutableList()
        if (chainOutbounds.isEmpty()) return
        if (chainOutbounds.size == 1) {
            chainOutbounds.first().tag = resolvedOutbound.tag
            if (prepend) v2rayConfig.outbounds.add(0, chainOutbounds.first()) else v2rayConfig.outbounds.add(chainOutbounds.first())
            existingTags.add(resolvedOutbound.tag)
            return
        }
        val chainTags = chainOutbounds.mapIndexed { index, _ -> if (index == 0) resolvedOutbound.tag else "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-$index" }
        if (chainTags.any { it in existingTags }) return
        chainOutbounds.forEachIndexed { index, outbound -> outbound.tag = chainTags[index] }
        for (i in 0 until chainOutbounds.size - 1) chainOutbounds[i].ensureSockopt().dialerProxy = chainOutbounds[i + 1].tag
        if (prepend) v2rayConfig.outbounds.addAll(0, chainOutbounds) else v2rayConfig.outbounds.addAll(chainOutbounds)
        chainOutbounds.forEach { existingTags.add(it.tag) }
    }

    private fun handlePolicyGroupResolvedOutbound(resolvedOutbound: CoreConfigContext.ResolvedOutbound, prepend: Boolean, existingTags: MutableSet<String>, v2rayConfig: V2rayConfig, policyGroupBalancerTags: MutableMap<String, String>, balancerStrategies: MutableList<BalancerStrategy>) {
        val memberPairs = resolvedOutbound.resolvedProfiles.mapNotNull { profile -> convertProfile2Outbound(profile)?.also { applyFakeSniIfEnabled(it, profile) }?.let { ob -> ob to profile } }
        if (memberPairs.isEmpty()) return
        val memberTagPrefix = "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-"
        val membersToAdd = mutableListOf<V2rayConfig.OutboundBean>()
        memberPairs.forEachIndexed { index, (outbound, profile) ->
            val memberTag = "$memberTagPrefix${index + 1}-${profile.remarks.trim()}"
            if (memberTag in existingTags) return@forEachIndexed
            outbound.tag = memberTag
            membersToAdd.add(outbound)
            existingTags.add(memberTag)
        }
        if (membersToAdd.isEmpty()) return
        if (prepend) v2rayConfig.outbounds.addAll(0, membersToAdd) else v2rayConfig.outbounds.addAll(membersToAdd)
        val balancerTag = if (resolvedOutbound.tag == AppConfig.TAG_PROXY) AppConfig.TAG_BALANCER else "${AppConfig.TAG_BALANCER_PRE}-${resolvedOutbound.tag}"
        val strategyType = BalancerStrategyType.from(resolvedOutbound.profile.policyGroupType)
        val fallbackTag = if (strategyType.supportsObservatory && resolvedOutbound.profile.policyGroupTestOutbounds != false) resolvedOutbound.profile.policyGroupFallbackTag?.takeIf { it.isNotEmpty() && it != AppConfig.TAG_PROXY } ?: membersToAdd.first().tag else null
        val strategy = buildBalancerStrategy(strategyType, listOf(memberTagPrefix), balancerTag, fallbackTag)
        val existingBalancers = v2rayConfig.routing.balancers?.toMutableList() ?: mutableListOf()
        if (existingBalancers.none { it.tag == balancerTag }) {
            existingBalancers.add(strategy.balancer)
            v2rayConfig.routing.balancers = existingBalancers
        }
        balancerStrategies.add(strategy)
        policyGroupBalancerTags[resolvedOutbound.tag] = balancerTag
    }

    private fun postProcessForSpeedtest(v2rayConfig: V2rayConfig) {
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.inbounds.clear(); v2rayConfig.routing.rules.clear(); v2rayConfig.dns = null; v2rayConfig.fakedns = null; v2rayConfig.stats = null; v2rayConfig.policy = null; v2rayConfig.outbounds.forEach { it.mux = null }
    }

    private fun toConfigResult(configContext: CoreConfigContext, v2rayConfig: V2rayConfig) = ConfigResult(true, configContext.guid, JsonUtil.toJsonPretty(v2rayConfig) ?: "")

    private fun initV2rayConfig(configContext: CoreConfigContext): V2rayConfig {
        val context = configContext.context
        val assets = if (needTun()) initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json") else initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json")
        if (TextUtils.isEmpty(assets)) error("Missing V2ray config template")
        if (needTun()) initConfigCacheWithTun = assets else initConfigCache = assets
        return JsonUtil.fromJson(assets, V2rayConfig::class.java) ?: error("Failed to parse config template")
    }

    private fun needTun() = SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()

    private fun configureInbounds(v2rayConfig: V2rayConfig) {
        val vpn = SettingsManager.isVpnMode(); val useHev = SettingsManager.isUsingHevTun(); val forcedByHev = vpn && useHev
        val forcedBySocksRoot = SettingsManager.isRootMode() || MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)
        val enableLocalProxy = forcedByHev || forcedBySocksRoot || MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
        val socksPort = SettingsManager.getSocksPort(); val socksUsername = SettingsManager.getSocksUsername(); val socksPassword = SettingsManager.getSocksPassword()
        val inbound1 = v2rayConfig.inbounds[0]
        if (inbound1.settings == null) inbound1.settings = V2rayConfig.InboundBean.InSettingsBean()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) != true) inbound1.listen = AppConfig.LOOPBACK
        inbound1.port = socksPort; inbound1.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        if (socksUsername != null && socksPassword != null) { inbound1.settings?.auth = "password"; inbound1.settings?.accounts = listOf(V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(socksUsername, socksPassword)) } else { inbound1.settings?.auth = "noauth"; inbound1.settings?.accounts = null }
        val fakedns = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        val sniffAllTlsAndHttp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false
        inbound1.sniffing?.enabled = fakedns || sniffAllTlsAndHttp
        inbound1.sniffing?.routeOnly = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
        if (!sniffAllTlsAndHttp) inbound1.sniffing?.destOverride?.clear()
        if (fakedns) inbound1.sniffing?.destOverride?.add("fakedns")
        if (!Utils.isXray()) {
            val inbound2 = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java) ?: error("Failed to clone inbound template")
            inbound2.tag = EConfigType.HTTP.name.lowercase(); inbound2.port = SettingsManager.getHttpPort(); inbound2.protocol = EConfigType.HTTP.name.lowercase(); inbound2.settings?.auth = null; inbound2.settings?.udp = null; v2rayConfig.inbounds.add(inbound2)
        }
        if (!enableLocalProxy) v2rayConfig.inbounds.removeIf { it.protocol == "socks" || it.protocol == "http" }
        if (needTun()) v2rayConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { it.settings?.mtu = SettingsManager.getVpnMtu(); it.sniffing = inbound1.sniffing }
    }

    private fun configureFakeDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) v2rayConfig.fakedns = listOf(V2rayConfig.FakednsBean())
    }

    private fun configureDns(configContext: CoreConfigContext, v2rayConfig: V2rayConfig, policyGroupBalancerTags: Map<String, String>) {
        val servers = ArrayList<Any>(); val remoteDns = SettingsManager.getRemoteDnsServers(); val domesticDns = SettingsManager.getDomesticDnsServers(); remoteDns.forEach { servers.add(it) }
        val hosts = buildDnsHostsFromRoutingRules(configContext); val cnDomesticDnsTags = buildDnsCnModeFromRoutingRules(configContext, servers, domesticDns); val domesticDnsTags = buildDnsFromRoutingRules(configContext, servers, remoteDns, domesticDns); domesticDnsTags.addAll(cnDomesticDnsTags)
        v2rayConfig.dns = V2rayConfig.DnsBean(servers = servers, hosts = hosts, tag = AppConfig.TAG_DNS, enableParallelQuery = if ((domesticDns.size + remoteDns.size) > 2) true else null)
        if (domesticDnsTags.isNotEmpty()) v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_DIRECT, inboundTag = ArrayList(domesticDnsTags)))
        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]
        if (dnsProxyBalancerTag != null) v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(balancerTag = dnsProxyBalancerTag, inboundTag = arrayListOf(AppConfig.TAG_DNS))) else v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_PROXY, inboundTag = arrayListOf(AppConfig.TAG_DNS)))
    }

    private fun buildDnsHostsFromRoutingRules(configContext: CoreConfigContext): MutableMap<String, Any> {
        val hosts = mutableMapOf<String, Any>(); val blockDomains = configContext.routingDomainRules.asSequence().filter { it.outboundTag == AppConfig.TAG_BLOCKED }.flatMap { it.domain.asSequence() }.toList(); if (blockDomains.isNotEmpty()) hosts.putAll(blockDomains.map { it to AppConfig.LOOPBACK })
        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN; hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES; hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES; hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES; hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES; hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES; hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES; hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES; hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES; hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES; hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES; hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES; hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES; hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES; hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES
        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) hosts.putAll(userHosts?.split(",").orEmpty().filter { it.isNotBlank() && it.contains(":") }.associate { val parts = it.split(":", limit = 2); parts[0].trim() to parts[1].trim() })
        return hosts
    }

    private fun buildDnsCnModeFromRoutingRules(configContext: CoreConfigContext, servers: ArrayList<Any>, domesticDns: List<String>): List<String> {
        val cnRegionFilter = { domain: String -> domain.startsWith("geosite:") && (domain.endsWith("-cn") || domain.endsWith("@cn")) || domain == AppConfig.GEOSITE_CN }
        if (!configContext.routingDomainRules.asSequence().filter { it.outboundTag == AppConfig.TAG_DIRECT }.flatMap { it.domain.asSequence() }.any { it == AppConfig.GEOSITE_CN }) return emptyList()
        val geoipCn = arrayListOf(AppConfig.GEOIP_CN); val cnDomains = configContext.routingDomainRules.asSequence().filter { it.outboundTag == AppConfig.TAG_DIRECT }.flatMap { it.domain.asSequence() }.filter { cnRegionFilter(it) }.toList(); if (cnDomains.isEmpty()) return emptyList()
        val tags = mutableListOf<String>(); domesticDns.forEachIndexed { index, address -> val tag = "${AppConfig.TAG_DOMESTIC_DNS}_cn_expect_$index"; servers.add(V2rayConfig.DnsBean.ServersBean(address = address, domains = cnDomains, expectIPs = geoipCn, skipFallback = true, tag = tag)); tags.add(tag) }; return tags
    }

    private fun buildDnsFromRoutingRules(configContext: CoreConfigContext, servers: ArrayList<Any>, remoteDns: List<String>, domesticDns: List<String>): MutableList<String> {
        val tags = mutableListOf<String>(); configContext.routingDomainRules.forEachIndexed { ruleIndex, rule -> when (rule.outboundTag) { AppConfig.TAG_DIRECT -> domesticDns.forEachIndexed { dnsIndex, address -> val tag = "${AppConfig.TAG_DOMESTIC_DNS}_${ruleIndex}_$dnsIndex"; servers.add(V2rayConfig.DnsBean.ServersBean(address = address, domains = rule.domain, skipFallback = true, tag = tag)); tags.add(tag) }; AppConfig.TAG_BLOCKED -> Unit; else -> servers.add(V2rayConfig.DnsBean.ServersBean(address = remoteDns.first(), domains = rule.domain)) } }; return tags
    }

    private fun configureLocalDns(configContext: CoreConfigContext, v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) != true) return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) {
            val geositeCn = arrayListOf(AppConfig.GEOSITE_CN); val routingDomains = configContext.routingDomainRules.asSequence().filter { it.outboundTag != AppConfig.TAG_BLOCKED }.flatMap { it.domain.asSequence() }.toList(); v2rayConfig.dns?.servers?.add(0, V2rayConfig.DnsBean.ServersBean(address = "fakedns", domains = geositeCn + routingDomains))
        }
        if (SettingsManager.isVpnMode()) {
            val inboundTag = if (SettingsManager.isUsingHevTun()) "socks" else "tun"; v2rayConfig.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(inboundTag = arrayListOf(inboundTag), outboundTag = "dns-out", port = "53"))
        }
        if (v2rayConfig.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) v2rayConfig.outbounds.add(V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out", settings = null, streamSettings = null, mux = null))
    }

    private fun configureRootModeDns(v2rayConfig: V2rayConfig) {
        if (!SettingsManager.isRootMode()) return
        if (v2rayConfig.routing.rules.none { it.outboundTag == "dns-out" && it.port == "53" }) v2rayConfig.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(inboundTag = arrayListOf("socks"), outboundTag = "dns-out", port = "53"))
        if (v2rayConfig.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) v2rayConfig.outbounds.add(V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out", settings = null, streamSettings = null, mux = null))
    }

    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) { if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) { v2rayConfig.stats = null; v2rayConfig.policy = null } }

    private fun resolveOutboundDomainsToHosts(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "1") return
        val proxyOutboundList = v2rayConfig.getAllProxyOutbound(); val dns = v2rayConfig.dns ?: return; val newHosts = dns.hosts?.toMutableMap() ?: mutableMapOf(); val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true
        for (item in proxyOutboundList) {
            val domain = item.getServerAddress() ?: continue
            if (newHosts.containsKey(domain)) { item.ensureSockopt().domainStrategy = "UseIP"; item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(prioritizeIPv6 = preferIpv6, interleave = 2); continue }
            val resolvedIps = HttpUtil.resolveHostToIP(domain, preferIpv6) ?: continue
            if (resolvedIps.isEmpty()) continue
            item.ensureSockopt().domainStrategy = "UseIP"; item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(prioritizeIPv6 = preferIpv6, interleave = 2); newHosts[domain] = if (resolvedIps.size == 1) resolvedIps[0] else resolvedIps
        }
        dns.hosts = newHosts
    }

    private fun convertProfile2Outbound(profileItem: ProfileItem): V2rayConfig.OutboundBean? = CoreOutboundBuilder.convert(profileItem)

    private fun configureRouting(configContext: CoreConfigContext, v2rayConfig: V2rayConfig, policyGroupBalancerTags: Map<String, String>) {
        v2rayConfig.routing.domainStrategy = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: "AsIs"
        MmkvManager.decodeRoutingRulesets()?.forEach { appendRoutingUserRule(configContext, it, v2rayConfig, policyGroupBalancerTags) }
    }

    private fun appendRoutingUserRule(configContext: CoreConfigContext, item: RulesetItem?, v2rayConfig: V2rayConfig, policyGroupBalancerTags: Map<String, String>) {
        val context = configContext.context; if (item == null || !item.enabled) return
        val rule = JsonUtil.fromJson(JsonUtil.toJson(item), V2rayConfig.RoutingBean.RulesBean::class.java) ?: return
        rule.ip?.let { ipList -> rule.ip = ArrayList<String>().apply { ipList.forEach { add(when (it) { AppConfig.GEOIP_CN -> "ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn"; AppConfig.GEOIP_PRIVATE -> "ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private"; else -> it }) } } }
        if (SettingsManager.canUseProcessRouting()) rule.process?.let { rule.process = PackageUidResolver.packageNamesToUids(context, it).ifEmpty { null } } else rule.process = null
        val outboundTag = rule.outboundTag; policyGroupBalancerTags[outboundTag]?.let { rule.outboundTag = null; rule.balancerTag = it }
        if (!outboundTag.isNullOrBlank() && outboundTag !in policyGroupBalancerTags && outboundTag !in AppConfig.BUILTIN_OUTBOUND_TAGS && v2rayConfig.outbounds.none { it.tag == outboundTag }) { LogUtil.w(AppConfig.TAG, "Outbound tag '$outboundTag' not found, falling back to '${AppConfig.TAG_PROXY}'"); rule.outboundTag = AppConfig.TAG_PROXY }
        v2rayConfig.routing.rules.add(rule)
    }

    private fun buildBalancerStrategy(strategyType: BalancerStrategyType, selector: List<String>, balancerTag: String = AppConfig.TAG_BALANCER, fallbackTag: String? = null): BalancerStrategy {
        val probeUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
        val leastPingInterval = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
        val leastLoadInterval = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
        val leastLoadMethod = MmkvManager.decodeSettingsString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
        val leastLoadSampling = decodeObservatorySampling(); val leastLoadTimeout = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)
        val balancer = V2rayConfig.RoutingBean.BalancerBean(tag = balancerTag, selector = selector, fallbackTag = fallbackTag, strategy = V2rayConfig.RoutingBean.StrategyObject(type = strategyType.policyGroupType))
        val observatory = if (strategyType.requiresObservatory || fallbackTag != null) V2rayConfig.ObservatoryObject(subjectSelector = selector, probeUrl = probeUrl, probeInterval = leastPingInterval, enableConcurrency = true) else null
        val burstObservatory = if (strategyType.requiresBurstObservatory) V2rayConfig.BurstObservatoryObject(subjectSelector = selector, pingConfig = V2rayConfig.BurstObservatoryObject.PingConfigObject(destination = probeUrl, httpMethod = leastLoadMethod, interval = leastLoadInterval, sampling = leastLoadSampling, timeout = leastLoadTimeout)) else null
        return BalancerStrategy(balancer, observatory, burstObservatory)
    }

    private fun decodeObservatoryDuration(key: String, default: String): String { val value = MmkvManager.decodeSettingsString(key)?.trim(); return if (!value.isNullOrEmpty() && AppConfig.OBSERVATORY_DURATION_PATTERN.matches(value)) value else default }
    private fun decodeObservatorySampling(): Int = MmkvManager.decodeSettingsString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING.toInt()
    private data class BalancerStrategy(val balancer: V2rayConfig.RoutingBean.BalancerBean, val observatory: V2rayConfig.ObservatoryObject? = null, val burstObservatory: V2rayConfig.BurstObservatoryObject? = null)
}
