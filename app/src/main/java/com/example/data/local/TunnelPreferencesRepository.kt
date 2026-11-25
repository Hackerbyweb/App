package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tunnelDataStore: DataStore<Preferences> by preferencesDataStore(name = "hopshield_prefs")

enum class SplitTunnelMode(val label: String, val description: String) {
    ALL_APPS("Route All Apps", "All device apps pass through the multi-hop tunnel"),
    BYPASS_SELECTED("Bypass Selected", "Selected apps connect directly outside the VPN"),
    ALLOW_ONLY("Include Selected Only", "Only selected apps use the multi-hop tunnel")
}

data class TunnelSettings(
    val activeChainId: Long = -1L,
    val primaryDns: String = "1.1.1.1",
    val secondaryDns: String = "9.9.9.9",
    val mtu: Int = 1420,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet(),
    val blockLanTraffic: Boolean = true,
    val ipv6LeakProtection: Boolean = true,
    val stealthDarkTheme: Boolean = true,
    val localTunInspectionEnabled: Boolean = true
)

class TunnelPreferencesRepository(private val context: Context) {

    private object Keys {
        val ACTIVE_CHAIN_ID = longPreferencesKey("active_chain_id")
        val PRIMARY_DNS = stringPreferencesKey("primary_dns")
        val SECONDARY_DNS = stringPreferencesKey("secondary_dns")
        val MTU = intPreferencesKey("mtu")
        val SPLIT_MODE = stringPreferencesKey("split_mode")
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val BLOCK_LAN = booleanPreferencesKey("block_lan")
        val IPV6_LEAK_PROTECTION = booleanPreferencesKey("ipv6_leak_protection")
        val STEALTH_DARK = booleanPreferencesKey("stealth_dark")
        val LOCAL_TUN_INSPECTION = booleanPreferencesKey("local_tun_inspection")
    }

    val settingsFlow: Flow<TunnelSettings> = context.tunnelDataStore.data.map { prefs ->
        val splitModeName = prefs[Keys.SPLIT_MODE] ?: SplitTunnelMode.ALL_APPS.name
        TunnelSettings(
            activeChainId = prefs[Keys.ACTIVE_CHAIN_ID] ?: -1L,
            primaryDns = prefs[Keys.PRIMARY_DNS] ?: "1.1.1.1",
            secondaryDns = prefs[Keys.SECONDARY_DNS] ?: "9.9.9.9",
            mtu = prefs[Keys.MTU] ?: 1420,
            splitTunnelMode = SplitTunnelMode.entries.firstOrNull { it.name == splitModeName }
                ?: SplitTunnelMode.ALL_APPS,
            selectedPackages = prefs[Keys.SELECTED_PACKAGES] ?: emptySet(),
            blockLanTraffic = prefs[Keys.BLOCK_LAN] ?: true,
            ipv6LeakProtection = prefs[Keys.IPV6_LEAK_PROTECTION] ?: true,
            stealthDarkTheme = prefs[Keys.STEALTH_DARK] ?: true,
            localTunInspectionEnabled = prefs[Keys.LOCAL_TUN_INSPECTION] ?: true
        )
    }

    suspend fun getSnapshot(): TunnelSettings = settingsFlow.first()

    suspend fun setActiveChainId(chainId: Long) {
        context.tunnelDataStore.edit { it[Keys.ACTIVE_CHAIN_ID] = chainId }
    }

    suspend fun setDnsServers(primary: String, secondary: String) {
        context.tunnelDataStore.edit {
            it[Keys.PRIMARY_DNS] = primary
            it[Keys.SECONDARY_DNS] = secondary
        }
    }

    suspend fun setMtu(mtu: Int) {
        context.tunnelDataStore.edit { it[Keys.MTU] = mtu.coerceIn(1280, 1500) }
    }

    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) {
        context.tunnelDataStore.edit { it[Keys.SPLIT_MODE] = mode.name }
    }

    suspend fun togglePackageSelection(packageName: String) {
        context.tunnelDataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(packageName)) {
                current.remove(packageName)
            }
            prefs[Keys.SELECTED_PACKAGES] = current
        }
    }

    suspend fun setBlockLanTraffic(enabled: Boolean) {
        context.tunnelDataStore.edit { it[Keys.BLOCK_LAN] = enabled }
    }

    suspend fun setIpv6LeakProtection(enabled: Boolean) {
        context.tunnelDataStore.edit { it[Keys.IPV6_LEAK_PROTECTION] = enabled }
    }

    suspend fun setStealthDarkTheme(enabled: Boolean) {
        context.tunnelDataStore.edit { it[Keys.STEALTH_DARK] = enabled }
    }
}
