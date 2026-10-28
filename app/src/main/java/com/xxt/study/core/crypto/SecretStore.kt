package com.xxt.study.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机加密存储：Cookie、AI 密钥这类东西一律走这里。
 *
 * 密钥由 AndroidKeyStore 生成并托管，不出安全硬件；卸载即失效。
 * 存的是 Base64(IV || ciphertext)，IV 每次随机。
 *
 * 任何一步失败都返回 null / false，绝不抛给上层——隐私存储坏了不该让 App 崩。
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("study_secrets", Context.MODE_PRIVATE)

    fun put(name: String, plain: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    fun get(name: String): String? = runCatching {
        val raw = prefs.getString(name, null) ?: return null
        val blob = Base64.decode(raw, Base64.NO_WRAP)
        if (blob.size <= IV_SIZE) return null
        val iv = blob.copyOfRange(0, IV_SIZE)
        val ct = blob.copyOfRange(IV_SIZE, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    fun has(name: String): Boolean = prefs.contains(name)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "xxt_study_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
