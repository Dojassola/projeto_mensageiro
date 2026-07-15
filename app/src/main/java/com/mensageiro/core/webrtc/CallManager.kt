package com.mensageiro.core.webrtc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.mensageiro.MainActivity
import com.mensageiro.R
import com.mensageiro.core.CallActionReceiver
import com.mensageiro.core.CallService
import java.util.UUID
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class CallManager(
    context: Context,
    private val peerId: String,
    private val contactName: String,
    private val isConnected: () -> Boolean,
    private val send: (CallControl) -> Unit,
    private val onChanged: () -> Unit,
    private val onHistory: (String) -> Unit
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val factory = P2pMessenger.peerConnectionFactory(app)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val pendingIce = mutableListOf<IceCandidate>()
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var initiator = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    @Volatile var state = CallState.IDLE
        private set
    @Volatile var callId: String? = null
        private set
    @Volatile var startedAt = 0L
        private set
    @Volatile var muted = false
        private set
    @Volatile var speakerOn = false
        private set

    fun start(): String {
        if (!isConnected()) return "Contato desconectado."
        if (!hasMicrophonePermission()) return "Permita o uso do microfone."
        if (state != CallState.IDLE) return "Ja existe uma chamada em andamento."
        callId = UUID.randomUUID().toString()
        onHistory("Chamada realizada")
        setState(CallState.CALLING)
        send(CallControl(callId!!, CallAction.INVITE))
        scheduleTimeout(callId!!)
        return ""
    }

    fun accept(): String {
        val id = callId ?: return "Chamada indisponivel."
        if (state != CallState.RINGING) return "Chamada indisponivel."
        if (!hasMicrophonePermission()) return "Permita o uso do microfone."
        cancelIncomingNotification()
        preparePeer(id, false)
        setState(CallState.CONNECTING)
        send(CallControl(id, CallAction.ACCEPT))
        return ""
    }

    fun reject() {
        val id = callId ?: return
        send(CallControl(id, CallAction.REJECT))
        closeCall("Chamada recusada")
    }

    fun end() {
        val id = callId ?: return
        val action = if (state == CallState.CALLING) CallAction.CANCEL else CallAction.END
        send(CallControl(id, action))
        closeCall(if (state == CallState.CALLING) "Chamada cancelada" else null)
    }

    fun receive(control: CallControl) {
        when (control.action) {
            CallAction.INVITE -> receiveInvite(control.callId)
            CallAction.ACCEPT -> if (matches(control) && state == CallState.CALLING) {
                cancelIncomingNotification()
                preparePeer(control.callId, true)
                setState(CallState.CONNECTING)
                createOffer()
            }
            CallAction.OFFER -> if (matches(control)) receiveOffer(control.payload ?: return)
            CallAction.ANSWER -> if (matches(control)) receiveAnswer(control.payload ?: return)
            CallAction.ICE -> if (matches(control)) receiveIce(control.payload ?: return)
            CallAction.REJECT -> if (matches(control)) closeCall("Chamada recusada")
            CallAction.CANCEL -> if (matches(control)) closeCall("Chamada perdida")
            CallAction.BUSY -> if (matches(control)) closeCall("Contato ocupado")
            CallAction.END -> if (matches(control)) closeCall()
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        audioTrack?.setEnabled(!value)
        changed()
    }

    fun setSpeaker(value: Boolean) {
        speakerOn = value
        routeAudio(value)
        changed()
    }

    fun disconnect() = closeCall()

    private fun receiveInvite(id: String) {
        if (state != CallState.IDLE) {
            send(CallControl(id, CallAction.BUSY))
            return
        }
        callId = id
        onHistory("Chamada recebida")
        setState(CallState.RINGING)
        showIncomingNotification()
        scheduleTimeout(id)
    }

    private fun preparePeer(id: String, outgoing: Boolean) {
        closeMedia()
        initiator = outgoing
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
        routeAudio(false)
        runCatching { CallService.start(app, contactName, peerId) }

        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = factory.createAudioTrack("call-audio-$id", source).also {
            it.setEnabled(!muted)
        }
        audioTrack = track
        peer = checkNotNull(factory.createPeerConnection(P2pMessenger.rtcConfiguration(), Observer())).also {
            it.addTrack(track, listOf("call-$id"))
        }
    }

    private fun createOffer(iceRestart: Boolean = false) {
        val activePeer = peer ?: return
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }
        activePeer.createOffer(Sdp(created = { offer ->
            activePeer.setLocalDescription(Sdp(set = {
                callId?.let { send(CallControl(it, CallAction.OFFER, offer.description)) }
            }), offer)
        }), constraints)
    }

    private fun receiveOffer(sdp: String) {
        val activePeer = peer ?: return
        activePeer.setRemoteDescription(Sdp(set = {
            flushIce()
            activePeer.createAnswer(Sdp(created = { answer ->
                activePeer.setLocalDescription(Sdp(set = {
                    callId?.let { send(CallControl(it, CallAction.ANSWER, answer.description)) }
                }), answer)
            }), MediaConstraints())
        }), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun receiveAnswer(sdp: String) {
        peer?.setRemoteDescription(
            Sdp(set = ::flushIce),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun receiveIce(payload: String) {
        runCatching {
            val value = JSONObject(payload)
            IceCandidate(value.getString("mid"), value.getInt("line"), value.getString("sdp"))
        }.onSuccess { candidate ->
            if (peer?.remoteDescription == null) pendingIce += candidate else peer?.addIceCandidate(candidate)
        }
    }

    private fun flushIce() {
        pendingIce.forEach { peer?.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun sendIce(candidate: IceCandidate) {
        val id = callId ?: return
        val payload = JSONObject()
            .put("mid", candidate.sdpMid)
            .put("line", candidate.sdpMLineIndex)
            .put("sdp", candidate.sdp)
            .toString()
        send(CallControl(id, CallAction.ICE, payload))
    }

    private fun closeCall(result: String? = null) {
        val previousState = state
        if (previousState == CallState.IDLE && callId == null) return
        val duration = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0
        state = CallState.IDLE
        callId = null
        startedAt = 0
        muted = false
        speakerOn = false
        cancelIncomingNotification()
        closeMedia()
        onHistory(result ?: if (duration > 0) "Chamada encerrada - ${formatDuration(duration)}" else "Chamada encerrada")
        changed()
    }

    private fun closeMedia() {
        val closingPeer = peer
        val closingTrack = audioTrack
        val closingSource = audioSource
        peer = null
        audioTrack = null
        audioSource = null
        closingPeer?.close()
        closingPeer?.dispose()
        closingTrack?.dispose()
        closingSource?.dispose()
        pendingIce.clear()
        clearAudioRoute()
        abandonAudioFocus()
        audioManager.mode = previousAudioMode
        CallService.stop(app)
    }

    private fun matches(control: CallControl) = control.callId == callId

    private fun setState(value: CallState) {
        state = value
        if (value == CallState.ACTIVE && startedAt == 0L) startedAt = System.currentTimeMillis()
        changed()
    }

    private fun changed() = main.post(onChanged)

    private fun scheduleTimeout(id: String) {
        main.postDelayed({
            if (callId == id && (state == CallState.CALLING || state == CallState.RINGING)) end()
        }, CallTimeout)
    }

    private fun hasMicrophonePermission() =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
                .also(audioManager::requestAudioFocus)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
    }

    private fun routeAudio(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                val preferredTypes = if (speaker) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
                    listOf(
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    )
                }
                preferredTypes.firstNotNullOfOrNull { type ->
                    audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
                }?.let(audioManager::setCommunicationDevice)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    private fun clearAudioRoute() {
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
        else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    private fun formatDuration(duration: Long): String =
        "%02d:%02d".format(duration / 60_000, duration / 1_000 % 60)

    private fun showIncomingNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CallChannel, "Chamadas", NotificationManager.IMPORTANCE_HIGH)
        )
        val openApp = PendingIntent.getActivity(
            app,
            contactName.hashCode(),
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(app, CallChannel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Chamada recebida")
            .setContentText(contactName)
            .setContentIntent(openApp)
            .addAction(
                0,
                "Recusar",
                CallActionReceiver.pendingIntent(app, peerId, CallActionReceiver.Reject)
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (hasMicrophonePermission()) {
            builder.addAction(
                0,
                "Atender",
                CallActionReceiver.pendingIntent(app, peerId, CallActionReceiver.Accept)
            )
        }
        manager.notify(
            IncomingNotification,
            builder.build()
        )
    }

    private fun cancelIncomingNotification() {
        app.getSystemService(NotificationManager::class.java).cancel(IncomingNotification)
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(value: PeerConnection.IceConnectionState) {
            when (value) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> setState(CallState.ACTIVE)
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    setState(CallState.RECONNECTING)
                    if (initiator) {
                        peer?.restartIce()
                        createOffer(true)
                    }
                }
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED -> closeCall()
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = sendIce(candidate)
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private class Sdp(
        private val created: (SessionDescription) -> Unit = {},
        private val set: () -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = created(description)
        override fun onSetSuccess() = set()
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private companion object {
        const val CallTimeout = 45_000L
        const val CallChannel = "mensageiro_calls"
        const val IncomingNotification = 3_002
    }
}
