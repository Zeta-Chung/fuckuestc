package edu.uestc.checkin.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import edu.uestc.checkin.model.CheckinConfig
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("private_config", Context.MODE_PRIVATE)
    private val alias = "authorized_checkin_config_v1"

    fun save(config: CheckinConfig) {
        require(config.validate().isEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(config.toStoredJson().toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(preferences.edit().putString("encrypted", Base64.getEncoder().encodeToString(packed)).commit())
    }

    fun load(): CheckinConfig? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        return try {
            val packed = Base64.getDecoder().decode(encoded)
            if (packed.size < 13) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            val plain = cipher.doFinal(packed.copyOfRange(12, packed.size))
            CheckinConfig.fromStoredJson(JSONObject(plain.toString(Charsets.UTF_8)))
        } catch (_: Exception) { null }
    }

    fun clear() { preferences.edit().remove("encrypted").apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
