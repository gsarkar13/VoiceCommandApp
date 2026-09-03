package com.voicecommand.partner.command

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactLookup {
    data class Contact(val name: String, val number: String)

    fun findBest(context: Context, query: String): Contact? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val q = CommandParser.normalize(query)
        if (q.isEmpty()) return null
        val qTokens = q.split(" ").filter { it.isNotBlank() }
        var best: Pair<Contact, Int>? = null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                val type = cursor.getInt(2)
                val isPrimary = cursor.getInt(3) == 1
                val nameLower = CommandParser.normalize(name)
                var score = 0
                when {
                    nameLower == q -> score = 100
                    nameLower.startsWith(q) -> score = 85
                    nameLower.contains(q) -> score = 70
                    qTokens.size > 1 && qTokens.all { nameLower.contains(it) } -> score = 65
                }
                if (score == 0) continue
                if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) score += 5
                if (isPrimary) score += 5
                if (best == null || score > best!!.second) {
                    best = Contact(name, number) to score
                }
            }
        }
        return best?.takeIf { it.second >= 60 }?.first
    }
}
