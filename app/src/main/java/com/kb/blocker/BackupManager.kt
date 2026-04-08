package com.kb.blocker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings Backup & Restore
 * সব settings JSON এ export/import করা যাবে
 */
object BackupManager {

    fun export(ctx: Context, uri: Uri): Boolean {
        return try {
            val json = JSONObject().apply {
                // Keywords
                put("keywords", JSONArray(KeywordService.loadKeywords(ctx)))
                // Whitelist
                put("whitelist", JSONArray(KeywordService.loadWhitelist(ctx)))
                // Feature flags
                put("enabled",      KeywordService.isEnabled(ctx))
                put("adult_text",   KeywordService.isAdultTextDetectEnabled(ctx))
                put("soft_adult",   KeywordService.isSoftAdultEnabled(ctx))
                put("video_meta",   KeywordService.isVideoMetaEnabled(ctx))
                put("vpn_block",    VpnDetector.isBlockEnabled(ctx))
                put("safe_search",  SafeSearchManager.isEnabled(ctx))
                put("install_block",AppInstallBlocker.isEnabled(ctx))
                put("usage_limit",  UsageLimitManager.isEnabled(ctx))
                // Schedule
                put("schedule_enabled", ScheduleManager.isScheduleEnabled(ctx))
                val (sh,sm) = ScheduleManager.getStartTime(ctx)
                val (eh,em) = ScheduleManager.getEndTime(ctx)
                put("schedule_start_h", sh); put("schedule_start_m", sm)
                put("schedule_end_h",   eh); put("schedule_end_m",   em)
                // Meta
                put("backup_date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                put("version", "1.0")
            }

            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { it.write(json.toString(2)) }
            }
            true
        } catch (_: Exception) { false }
    }

    data class RestoreResult(
        val success: Boolean,
        val keywords: Int = 0,
        val whitelist: Int = 0,
        val message: String = ""
    )

    fun restore(ctx: Context, uri: Uri): RestoreResult {
        return try {
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            } ?: return RestoreResult(false, message = "File পড়া যায়নি")

            val json = JSONObject(raw)

            // Keywords
            val kwArr = json.optJSONArray("keywords")
            val kws   = mutableListOf<String>()
            if (kwArr != null) for (i in 0 until kwArr.length()) kws.add(kwArr.getString(i))
            if (kws.isNotEmpty()) KeywordService.saveKeywords(ctx, kws)

            // Whitelist
            val wlArr = json.optJSONArray("whitelist")
            val wls   = mutableListOf<String>()
            if (wlArr != null) for (i in 0 until wlArr.length()) wls.add(wlArr.getString(i))
            if (wls.isNotEmpty()) KeywordService.saveWhitelist(ctx, wls)

            // Feature flags
            if (json.has("enabled"))       KeywordService.setEnabled(ctx,           json.getBoolean("enabled"))
            if (json.has("adult_text"))    KeywordService.setAdultTextDetect(ctx,   json.getBoolean("adult_text"))
            if (json.has("soft_adult"))    KeywordService.setSoftAdult(ctx,         json.getBoolean("soft_adult"))
            if (json.has("video_meta"))    KeywordService.setVideoMeta(ctx,         json.getBoolean("video_meta"))
            if (json.has("vpn_block"))     VpnDetector.setBlockEnabled(ctx,         json.getBoolean("vpn_block"))
            if (json.has("safe_search"))   SafeSearchManager.setEnabled(ctx,        json.getBoolean("safe_search"))
            if (json.has("install_block")) AppInstallBlocker.setEnabled(ctx,        json.getBoolean("install_block"))
            if (json.has("usage_limit"))   UsageLimitManager.setEnabled(ctx,        json.getBoolean("usage_limit"))

            // Schedule
            if (json.has("schedule_enabled")) ScheduleManager.setScheduleEnabled(ctx, json.getBoolean("schedule_enabled"))
            if (json.has("schedule_start_h")) ScheduleManager.setStartTime(ctx,
                json.getInt("schedule_start_h"), json.optInt("schedule_start_m", 0))
            if (json.has("schedule_end_h")) ScheduleManager.setEndTime(ctx,
                json.getInt("schedule_end_h"), json.optInt("schedule_end_m", 0))

            RestoreResult(true, keywords = kws.size, whitelist = wls.size,
                message = "✅ ${kws.size} keyword, ${wls.size} whitelist restore হয়েছে")
        } catch (e: Exception) {
            RestoreResult(false, message = "Error: ${e.message}")
        }
    }
}
