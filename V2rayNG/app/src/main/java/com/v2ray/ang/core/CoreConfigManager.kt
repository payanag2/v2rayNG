package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AngApplication
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
        return try {
            val cc = CoreConfigContextBuilder.build(context, guid) ?: return ConfigResult(false, guid, errorMessage = "Failed to build config context")
            if (cc.isCustom) buildV2rayCustomConfig(cc) else toConfigResult(cc, buildUnifiedConfig(cc))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            ConfigResult(false, guid, errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        return try {
            val cc = CoreConfigContextBuilder.build(context, guid) ?: return ConfigResult(false, guid, errorMessage = "Failed to build config context")
            if (cc.isCustom) buildV2rayCustomConfig(cc) else buildUnifiedConfig(cc).also { postProcessForSpeedtest(it) }.let { toConfigResult(cc, it) }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            ConfigResult(false, guid, errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildV2rayCustomConfig(cc: CoreConfigContext): ConfigResult {
        val raw = MmkvManager.decodeServerRaw(cc.guid) ?: return ConfigResult(false, cc.guid, errorMessage = "Config is empty")
        val result = ConfigResult(true, cc.guid, raw)
        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return result
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            if (!json.has("stats")) json.add("stats", JsonObject())
            if (!json.has("policy")) json.add("policy", JsonObject().apply { add("system", JsonObject().apply { addProperty("statsOutboundUplink", true); addProperty("statsOutboundDownlink", true) }) })
        } else { json.remove("stats"); json.remove("policy") }
        if (!needTun()) return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, cc.guid, it) } ?: result
        if (SettingsManager.canUseProcessRouting()) {
            val rules = json.getAsJsonObject("routing")?.getAsJsonArray("rules") ?: JsonArray()
            rules.forEach { elem ->
                val rule = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val process = rule.getAsJsonArray("process") ?: return@forEach
                val names = process.mapNotNull { it.takeIf { p -> p.isJsonPrimitive && p.asJsonPrimitive.isString }?.asString }.takeIf { it.isNotEmpty() } ?: return@forEach
                val uids = PackageUidResolver.packageNamesToUids(cc.context, names).takeIf { it.isNotEmpty() } ?: return@forEach
                rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
            }
        }
        val inbounds = json.getAsJsonArray("inbounds") ?: JsonArray().also { json.add("inbounds", it) }
        if (inbounds.none { it.isJsonObject && it.asJsonObject.get("protocol")?.asString == "tun" }) {
            initV2rayConfig(cc).inbounds.firstOrNull { it.tag == "tun" }?.let { tun -> tun.settings?.mtu = SettingsManager.getVpnMtu(); inbounds.add(JsonUtil.parseString(JsonUtil.toJson(tun))) }
        }
        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, cc.guid, it) } ?: result
    }

    private fun buildUnifiedConfig(cc: CoreConfigContext): V2rayConfig {
        require(cc.resolvedOutbounds.isNotEmpty())
        val primary = cc.resolvedOutbounds.first()
        val config = initV2rayConfig(cc)
        config.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        config.remarks = primary.profile.remarks
        configureInbounds(config)
        if (config.outbounds.isNotEmpty()) config.outbounds.removeAt(0)
        val tags = config.outbounds.mapTo(mutableSetOf()) { it.tag }
        val groups = mutableMapOf<String, String>(); val strategies = mutableListOf<BalancerStrategy>()
        cc.resolvedOutbounds.forEachIndexed { i, spec -> buildOutbounds(i == 0, spec, tags, config, groups, strategies) }
        configureRouting(cc, config, groups); configureFakeDns(config); configureDns(cc, config, groups); configureLocalDns(cc, config); configureRootModeDns(config)
        if (primary.resolvedType == CoreResolvedType.POLICYGROUP) config.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(network = "tcp,udp", balancerTag = AppConfig.TAG_BALANCER))
        applyObservability(config, strategies); applySpeedDisabled(config); resolveOutboundDomainsToHosts(config)
        return config
    }

    private fun buildOutbounds(prepend: Boolean, spec: CoreConfigContext.ResolvedOutbound, tags: MutableSet<String>, config: V2rayConfig, groups: MutableMap<String, String>, strategies: MutableList<BalancerStrategy>) {
        if (spec.tag in tags) return
        when (spec.resolvedType) {
            CoreResolvedType.NORMAL -> handleNormal(spec, prepend, tags, config)
            CoreResolvedType.PROXYCHAIN -> handleChain(spec, prepend, tags, config)
            CoreResolvedType.POLICYGROUP -> handleGroup(spec, prepend, tags, config, groups, strategies)
        }
    }

    private fun handleNormal(spec: CoreConfigContext.ResolvedOutbound, prepend: Boolean, tags: MutableSet<String>, config: V2rayConfig) {
        val profile = spec.resolvedProfiles.firstOrNull() ?: return
        val outbound = convertProfile2Outbound(profile) ?: return
        applyFakeSniIfEnabled(outbound, profile)
        outbound.tag = spec.tag
        if (prepend) config.outbounds.add(0, outbound) else config.outbounds.add(outbound)
        tags.add(spec.tag)
    }

    /** Xray dials the local FakeSNI listener. No iptables interception is used. */
    private fun applyFakeSniIfEnabled(outbound: V2rayConfig.OutboundBean, profile: ProfileItem) {
        val prefs = FakeSniPreferences(AngApplication.application)
        if (!prefs.enabled || profile.configType == EConfigType.WIREGUARD || profile.security?.lowercase() != "tls") return
        val settings = outbound.settings ?: return
        if (settings.address == null || settings.port == null) return
        settings.address = AppConfig.LOOPBACK
        settings.port = prefs.listenPort
        outbound.streamSettings?.sockopt?.dialerProxy = null
        LogUtil.i(AppConfig.TAG, "FakeSNI: ${profile.remarks} -> 127.0.0.1:${prefs.listenPort}, Connect ${prefs.connectIp}:${prefs.connectPort}, SNI ${prefs.fakeSniHostname}")
    }

    private fun handleChain(spec: CoreConfigContext.ResolvedOutbound, prepend: Boolean, tags: MutableSet<String>, config: V2rayConfig) {
        val list = spec.resolvedProfiles.mapNotNull { p -> convertProfile2Outbound(p)?.also { applyFakeSniIfEnabled(it, p) } }.toMutableList()
        if (list.isEmpty()) return
        if (list.size == 1) { list[0].tag = spec.tag; if (prepend) config.outbounds.add(0, list[0]) else config.outbounds.add(list[0]); tags.add(spec.tag); return }
        val chainTags = list.mapIndexed { i, _ -> if (i == 0) spec.tag else "${AppConfig.TAG_PROXY}-${spec.tag}-$i" }
        if (chainTags.any { it in tags }) return
        list.forEachIndexed { i, ob -> ob.tag = chainTags[i] }
        for (i in 0 until list.size - 1) list[i].ensureSockopt().dialerProxy = list[i + 1].tag
        if (prepend) config.outbounds.addAll(0, list) else config.outbounds.addAll(list); list.forEach { tags.add(it.tag) }
    }

    private fun handleGroup(spec: CoreConfigContext.ResolvedOutbound, prepend: Boolean, tags: MutableSet<String>, config: V2rayConfig, groups: MutableMap<String, String>, strategies: MutableList<BalancerStrategy>) {
        val pairs = spec.resolvedProfiles.mapNotNull { p -> convertProfile2Outbound(p)?.also { applyFakeSniIfEnabled(it, p) }?.let { it to p } }
        if (pairs.isEmpty()) return
        val prefix = "${AppConfig.TAG_PROXY}-${spec.tag}-"; val members = mutableListOf<V2rayConfig.OutboundBean>()
        pairs.forEachIndexed { i, pair -> val tag = "$prefix${i + 1}-${pair.second.remarks.trim()}"; if (tag !in tags) { pair.first.tag = tag; members.add(pair.first); tags.add(tag) } }
        if (members.isEmpty()) return
        if (prepend) config.outbounds.addAll(0, members) else config.outbounds.addAll(members)
        val balancerTag = if (spec.tag == AppConfig.TAG_PROXY) AppConfig.TAG_BALANCER else "${AppConfig.TAG_BALANCER_PRE}-${spec.tag}"
        val type = BalancerStrategyType.from(spec.profile.policyGroupType); val fallback = if (type.supportsObservatory && spec.profile.policyGroupTestOutbounds != false) spec.profile.policyGroupFallbackTag?.takeIf { it.isNotEmpty() && it != AppConfig.TAG_PROXY } ?: members.first().tag else null
        val strategy = buildBalancerStrategy(type, listOf(prefix), balancerTag, fallback); val bs = config.routing.balancers?.toMutableList() ?: mutableListOf(); if (bs.none { it.tag == balancerTag }) { bs.add(strategy.balancer); config.routing.balancers = bs }; strategies.add(strategy); groups[spec.tag] = balancerTag
    }

    private fun postProcessForSpeedtest(config: V2rayConfig) { config.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"; config.inbounds.clear(); config.routing.rules.clear(); config.dns = null; config.fakedns = null; config.stats = null; config.policy = null; config.outbounds.forEach { it.mux = null } }
    private fun toConfigResult(cc: CoreConfigContext, config: V2rayConfig) = ConfigResult(true, cc.guid, JsonUtil.toJsonPretty(config) ?: "")
    private fun initV2rayConfig(cc: CoreConfigContext): V2rayConfig { val context = cc.context; val assets = if (needTun()) initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json") else initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json"); if (TextUtils.isEmpty(assets)) error("Missing V2ray config template"); if (needTun()) initConfigCacheWithTun = assets else initConfigCache = assets; return JsonUtil.fromJson(assets, V2rayConfig::class.java) ?: error("Failed to parse config template") }
    private fun needTun() = SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()
    private fun configureInbounds(config: V2rayConfig) { val inbound = config.inbounds[0]; if (inbound.settings == null) inbound.settings = V2rayConfig.InboundBean.InSettingsBean(); if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) != true) inbound.listen = AppConfig.LOOPBACK; inbound.port = SettingsManager.getSocksPort(); inbound.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true); val u = SettingsManager.getSocksUsername(); val p = SettingsManager.getSocksPassword(); if (u != null && p != null) { inbound.settings?.auth = "password"; inbound.settings?.accounts = listOf(V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(u, p)) } else { inbound.settings?.auth = "noauth"; inbound.settings?.accounts = null }; val sniff = MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false; inbound.sniffing?.enabled = sniff; inbound.sniffing?.routeOnly = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false); if (!sniff) inbound.sniffing?.destOverride?.clear(); if (!Utils.isXray()) { val h = JsonUtil.fromJson(JsonUtil.toJson(inbound), V2rayConfig.InboundBean::class.java) ?: return; h.tag = EConfigType.HTTP.name.lowercase(); h.port = SettingsManager.getHttpPort(); h.protocol = EConfigType.HTTP.name.lowercase(); config.inbounds.add(h) }; if (needTun()) config.inbounds.firstOrNull { it.tag == "tun" }?.settings?.mtu = SettingsManager.getVpnMtu() }
    private fun configureFakeDns(config: V2rayConfig) { if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) config.fakedns = listOf(V2rayConfig.FakednsBean()) }
    private fun configureDns(cc: CoreConfigContext, config: V2rayConfig, groups: Map<String, String>) { val servers = ArrayList<Any>(); val remote = SettingsManager.getRemoteDnsServers(); val domestic = SettingsManager.getDomesticDnsServers(); remote.forEach { servers.add(it) }; val hosts = buildDnsHostsFromRoutingRules(cc); val domesticTags = buildDnsFromRoutingRules(cc, servers, remote, domestic); config.dns = V2rayConfig.DnsBean(servers = servers, hosts = hosts, tag = AppConfig.TAG_DNS, enableParallelQuery = if ((domestic.size + remote.size) > 2) true else null); if (domesticTags.isNotEmpty()) config.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_DIRECT, inboundTag = ArrayList(domesticTags))); val b = groups[AppConfig.TAG_PROXY]; if (b != null) config.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(balancerTag = b, inboundTag = arrayListOf(AppConfig.TAG_DNS))) else config.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_PROXY, inboundTag = arrayListOf(AppConfig.TAG_DNS))) }
    private fun buildDnsHostsFromRoutingRules(cc: CoreConfigContext): MutableMap<String, Any> { val h = mutableMapOf<String, Any>(); cc.routingDomainRules.filter { it.outboundTag == AppConfig.TAG_BLOCKED }.flatMap { it.domain }.forEach { h[it] = AppConfig.LOOPBACK }; val user = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS); if (user.isNotNullEmpty()) user?.split(",").orEmpty().filter { it.contains(":") }.forEach { val x = it.split(":", limit = 2); h[x[0].trim()] = x[1].trim() }; return h }
    private fun buildDnsFromRoutingRules(cc: CoreConfigContext, servers: ArrayList<Any>, remote: List<String>, domestic: List<String>): MutableList<String> { val tags = mutableListOf<String>(); cc.routingDomainRules.forEachIndexed { i, r -> when (r.outboundTag) { AppConfig.TAG_DIRECT -> domestic.forEachIndexed { j, a -> val tag = "${AppConfig.TAG_DOMESTIC_DNS}_${i}_$j"; servers.add(V2rayConfig.DnsBean.ServersBean(address = a, domains = r.domain, skipFallback = true, tag = tag)); tags.add(tag) }; AppConfig.TAG_BLOCKED -> Unit; else -> if (remote.isNotEmpty()) servers.add(V2rayConfig.DnsBean.ServersBean(address = remote.first(), domains = r.domain)) } }; return tags }
    private fun configureLocalDns(cc: CoreConfigContext, config: V2rayConfig) { if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) != true) return; if (SettingsManager.isVpnMode()) { val tag = if (SettingsManager.isUsingHevTun()) "socks" else "tun"; config.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(inboundTag = arrayListOf(tag), outboundTag = "dns-out", port = "53")) }; if (config.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) config.outbounds.add(V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out", settings = null, streamSettings = null, mux = null)) }
    private fun configureRootModeDns(config: V2rayConfig) { if (!SettingsManager.isRootMode()) return; if (config.routing.rules.none { it.outboundTag == "dns-out" && it.port == "53" }) config.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(inboundTag = arrayListOf("socks"), outboundTag = "dns-out", port = "53")); if (config.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) config.outbounds.add(V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out", settings = null, streamSettings = null, mux = null)) }
    private fun applySpeedDisabled(config: V2rayConfig) { if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) { config.stats = null; config.policy = null } }
    private fun resolveOutboundDomainsToHosts(config: V2rayConfig) { if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "1") return; val dns = config.dns ?: return; val hosts = dns.hosts?.toMutableMap() ?: mutableMapOf(); val ipv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true; config.getAllProxyOutbound().forEach { ob -> val domain = ob.getServerAddress() ?: return@forEach; if (domain == AppConfig.LOOPBACK) return@forEach; val ips = HttpUtil.resolveHostToIP(domain, ipv6) ?: return@forEach; ob.ensureSockopt().domainStrategy = "UseIP"; ob.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(prioritizeIPv6 = ipv6, interleave = 2); hosts[domain] = if (ips.size == 1) ips[0] else ips }); dns.hosts = hosts }
    private fun convertProfile2Outbound(profile: ProfileItem) = CoreOutboundBuilder.convert(profile)
    private fun configureRouting(cc: CoreConfigContext, config: V2rayConfig, groups: Map<String, String>) { config.routing.domainStrategy = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: "AsIs"; MmkvManager.decodeRoutingRulesets()?.forEach { item -> if (item.enabled) { val rule = JsonUtil.fromJson(JsonUtil.toJson(item), V2rayConfig.RoutingBean.RulesBean::class.java) ?: return@forEach; if (SettingsManager.canUseProcessRouting()) rule.process?.let { rule.process = PackageUidResolver.packageNamesToUids(cc.context, it).ifEmpty { null } } else rule.process = null; groups[rule.outboundTag]?.let { rule.outboundTag = null; rule.balancerTag = it }; config.routing.rules.add(rule) } } }
    private fun buildBalancerStrategy(type: BalancerStrategyType, selector: List<String>, tag: String, fallback: String?) = BalancerStrategy(V2rayConfig.RoutingBean.BalancerBean(tag = tag, selector = selector, fallbackTag = fallback, strategy = V2rayConfig.RoutingBean.StrategyObject(type = type.policyGroupType)), null, null)
    private data class BalancerStrategy(val balancer: V2rayConfig.RoutingBean.BalancerBean, val observatory: V2rayConfig.ObservatoryObject? = null, val burstObservatory: V2rayConfig.BurstObservatoryObject? = null)
    private fun applyObservability(config: V2rayConfig, strategies: List<BalancerStrategy>) { val selectors = strategies.flatMap { it.observatory?.subjectSelector ?: emptyList() }.distinct(); if (selectors.isNotEmpty()) config.observatory = V2rayConfig.ObservatoryObject(selectors, AppConfig.DELAY_TEST_URL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL, true) }
}
