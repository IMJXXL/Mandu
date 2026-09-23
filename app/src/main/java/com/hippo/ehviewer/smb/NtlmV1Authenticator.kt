package com.hippo.ehviewer.smb

import com.hierynomus.asn1.types.primitive.ASN1ObjectIdentifier
import com.hierynomus.ntlm.NtlmConfig
import com.hierynomus.ntlm.NtlmException
import com.hierynomus.ntlm.messages.NtlmAuthenticate
import com.hierynomus.ntlm.messages.NtlmChallenge
import com.hierynomus.ntlm.messages.NtlmNegotiate
import com.hierynomus.ntlm.messages.NtlmNegotiateFlag
import com.hierynomus.protocol.commons.buffer.Buffer
import com.hierynomus.protocol.commons.buffer.Endian
import com.hierynomus.security.Cipher
import com.hierynomus.security.SecurityProvider
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticateResponse
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.Authenticator
import com.hierynomus.smbj.connection.ConnectionContext
import com.hierynomus.spnego.NegTokenInit
import com.hierynomus.spnego.NegTokenTarg
import com.hierynomus.spnego.SpnegoException
import com.hierynomus.spnego.SpnegoToken
import java.io.IOException
import java.nio.charset.Charset
import java.util.EnumSet
import java.util.Random

class NtlmV1Authenticator : Authenticator {

    private enum class State { NEGOTIATE, AUTHENTICATE, COMPLETE }

    private var securityProvider: SecurityProvider? = null
    private var random: Random? = null
    private var config: NtlmConfig? = null
    private var state: State = State.NEGOTIATE
    private var negotiateFlags: MutableSet<NtlmNegotiateFlag> = EnumSet.noneOf(NtlmNegotiateFlag::class.java)
    private var negotiateMessage: ByteArray? = null

    class Factory : com.hierynomus.protocol.commons.Factory.Named<Authenticator> {
        override fun getName(): String = NTLMSSP_OID
        override fun create(): NtlmV1Authenticator = NtlmV1Authenticator()
    }

    override fun init(config: SmbConfig) {
        this.securityProvider = config.securityProvider
        this.random = config.randomProvider
        this.config = config.ntlmConfig
        this.state = State.NEGOTIATE
        this.negotiateFlags = HashSet()
    }

    override fun supports(context: AuthenticationContext): Boolean {
        return context.javaClass == AuthenticationContext::class.java
    }

    override fun authenticate(
        context: AuthenticationContext,
        gssToken: ByteArray,
        connectionContext: ConnectionContext
    ): AuthenticateResponse? {
        return try {
            when (state) {
                State.COMPLETE -> null
                State.NEGOTIATE -> {
                    state = State.AUTHENTICATE
                    doNegotiate(context)
                }
                State.AUTHENTICATE -> {
                    val negTokenTarg = NegTokenTarg().read(gssToken)
                    val serverChallenge = NtlmChallenge()
                    serverChallenge.read(Buffer.PlainBuffer(negTokenTarg.responseToken, Endian.LE))
                    negotiateFlags.removeIf { !serverChallenge.negotiateFlags.contains(it) }
                    val resp = doAuthenticate(context, serverChallenge)
                    state = State.COMPLETE
                    resp
                }
            }
        } catch (e: Exception) {
            when (e) {
                is SpnegoException -> throw IOException(e)
                is Buffer.BufferException -> throw IOException(e)
                else -> throw e
            }
        }
    }

    private fun doNegotiate(context: AuthenticationContext): AuthenticateResponse {
        val response = AuthenticateResponse()
        negotiateFlags = EnumSet.of(
            NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_56,
            NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_UNICODE,
            NtlmNegotiateFlag.NTLMSSP_REQUEST_TARGET,
        )

        val ntlmNegotiate = NtlmNegotiate(
            negotiateFlags, context.domain,
            config?.workstationName, config?.windowsVersion, config?.isOmitVersion ?: false
        )
        val negTokenInit = NegTokenInit()
        negTokenInit.addSupportedMech(ASN1ObjectIdentifier(NTLMSSP_OID))
        val ntlmBuffer = Buffer.PlainBuffer(Endian.LE)
        ntlmNegotiate.write(ntlmBuffer)
        negotiateMessage = ntlmBuffer.compactData
        negTokenInit.setMechToken(negotiateMessage)
        response.negToken = negTokenInit
        response.negotiateFlags = negotiateFlags
        return response
    }

    private fun doAuthenticate(
        context: AuthenticationContext,
        serverChallenge: NtlmChallenge
    ): AuthenticateResponse {
        val response = AuthenticateResponse()
        val sp = securityProvider ?: throw NtlmException("Not initialized")
        val pwd = String(context.password)
        val ntowf = md4(sp, pwd.toByteArray(Charset.forName("UTF-16LE")))
        val serverChallengeBytes = serverChallenge.serverChallenge
        val ntResponse = desl(sp, ntowf, serverChallengeBytes)
        val lmowf = lmowf(sp, pwd)
        val lmResponse = desl(sp, lmowf, serverChallengeBytes)
        val msg = NtlmAuthenticate(
            lmResponse, ntResponse,
            context.username, context.domain,
            config?.workstationName, null,
            negotiateFlags, config?.windowsVersion
        )
        response.negToken = negTokenTarg(msg)
        response.negotiateFlags = negotiateFlags
        return response
    }

    private fun negTokenTarg(resp: NtlmAuthenticate): SpnegoToken {
        val targ = NegTokenTarg()
        val ntlmBuffer = Buffer.PlainBuffer(Endian.LE)
        resp.write(ntlmBuffer)
        targ.responseToken = ntlmBuffer.compactData
        return targ
    }

    companion object {
        private const val NTLMSSP_OID = "1.3.6.1.4.1.311.2.2.10"
        private val LM_SECRET = byteArrayOf(0x4B, 0x47, 0x53, 0x21, 0x40, 0x23, 0x24, 0x25)

        private fun md4(sp: SecurityProvider, data: ByteArray): ByteArray {
            val md4 = sp.getDigest("MD4")
            md4.update(data)
            return md4.digest()
        }

        private fun lmowf(sp: SecurityProvider, password: String): ByteArray {
            val upper = password.uppercase().toByteArray(Charset.forName("US-ASCII"))
            val bytes = if (upper.size != 14) upper.copyOf(14) else upper
            val leftCipher = desCipher(sp, bytes.copyOfRange(0, 7))
            val rightCipher = desCipher(sp, bytes.copyOfRange(7, 14))
            val lmHash = ByteArray(16)
            var off = leftCipher.update(LM_SECRET, 0, 8, lmHash, 0)
            off += leftCipher.doFinal(lmHash, off)
            off += rightCipher.update(LM_SECRET, 0, 8, lmHash, off)
            off += rightCipher.doFinal(lmHash, off)
            return lmHash
        }

        private fun desl(sp: SecurityProvider, key: ByteArray, challenge: ByteArray): ByteArray {
            val k1 = key.copyOfRange(0, 7)
            val k2 = if (key.size >= 14) key.copyOfRange(7, 14) else key.copyOfRange(7, key.size).copyOf(7)
            val k3Raw = if (key.size >= 16) key.copyOfRange(14, 16) else key.copyOfRange(14, key.size)
            val k3 = ByteArray(7)
            System.arraycopy(k3Raw, 0, k3, 0, k3Raw.size)
            val r1 = desBlock(sp, k1, challenge)
            val r2 = desBlock(sp, k2, challenge)
            val r3 = desBlock(sp, k3, challenge)
            val result = ByteArray(24)
            System.arraycopy(r1, 0, result, 0, 8)
            System.arraycopy(r2, 0, result, 8, 8)
            System.arraycopy(r3, 0, result, 16, 8)
            return result
        }

        private fun desBlock(sp: SecurityProvider, key7: ByteArray, challenge8: ByteArray): ByteArray {
            val cipher = desCipher(sp, key7)
            val out = ByteArray(8)
            var off = cipher.update(challenge8, 0, 8, out, 0)
            off += cipher.doFinal(out, off)
            return out
        }

        private fun desCipher(sp: SecurityProvider, key56: ByteArray): Cipher {
            val cipher = sp.getCipher("DES/ECB/NoPadding")
            cipher.init(Cipher.CryptMode.ENCRYPT, setupKey(key56))
            return cipher
        }

        private fun setupKey(key56: ByteArray): ByteArray {
            val key = ByteArray(8)
            key[0] = (key56[0].toInt() shr 1 and 0xff).toByte()
            key[1] = ((key56[0].toInt() and 0x01 shl 6 or (key56[1].toInt() and 0xff shr 2 and 0xff)) and 0xff).toByte()
            key[2] = ((key56[1].toInt() and 0x03 shl 5 or (key56[2].toInt() and 0xff shr 3 and 0xff)) and 0xff).toByte()
            key[3] = ((key56[2].toInt() and 0x07 shl 4 or (key56[3].toInt() and 0xff shr 4 and 0xff)) and 0xff).toByte()
            key[4] = ((key56[3].toInt() and 0x0f shl 3 or (key56[4].toInt() and 0xff shr 5 and 0xff)) and 0xff).toByte()
            key[5] = ((key56[4].toInt() and 0x1f shl 2 or (key56[5].toInt() and 0xff shr 6 and 0xff)) and 0xff).toByte()
            key[6] = ((key56[5].toInt() and 0x3f shl 1 or (key56[6].toInt() and 0xff shr 7 and 0xff)) and 0xff).toByte()
            key[7] = (key56[6].toInt() and 0x7f).toByte()
            for (i in key.indices) {
                key[i] = (key[i].toInt() shl 1).toByte()
            }
            return key
        }
    }
}