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
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.mensageiro.MainActivity
import com.mensageiro.R
import com.mensageiro.core.CallActionReceiver
import com.mensageiro.core.CallDirection
import com.mensageiro.core.CallEndedBy
import com.mensageiro.core.CallHistoryEvent
import com.mensageiro.core.CallHistoryStage
import com.mensageiro.core.CallResult
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
    private val onHistory: (CallHistoryEvent) -> Unit
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
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
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
        onHistory(
            CallHistoryEvent(callId!!, CallHistoryStage.STARTED, System.currentTimeMillis(), CallDirection.OUTGOING)
        )
        setState(CallState.CALLING)
        startCallingFeedback()
        send(CallControl(callId!!, CallAction.INVITE))
        scheduleTimeout(callId!!)
        return ""
    }

    fun accept(): String {
        val id = callId ?: return "Chamada indisponivel."
        if (state != CallState.RINGING) return "Chamada indisponivel."
        if (!hasMicrophonePermission()) return "Permita o uso do microfone."
        stopRinging()
        cancelIncomingNotification()
        preparePeer(id, false)
        setState(CallState.CONNECTING)
        send(CallControl(id, CallAction.ACCEPT))
        return ""
    }

    fun reject() {
        val id = callId ?: return
        send(CallControl(id, CallAction.REJECT))
        closeCall(CallResult.DECLINED, CallEndedBy.ME)
    }

    fun end() {
        val id = callId ?: return
        val calling = state == CallState.CALLING
        val action = if (calling) CallAction.CANCEL else CallAction.END
        send(CallControl(id, action))
        closeCall(if (calling) CallResult.CANCELED else CallResult.COMPLETED, CallEndedBy.ME)
    }

    fun receive(control: CallControl) {
        when (control.action) {
            CallAction.INVITE -> receiveInvite(control.callId)
            CallAction.ACCEPT -> if (matches(control) && state == CallState.CALLING) {
                stopRinging()
                cancelIncomingNotification()
                preparePeer(control.callId, true)
                setState(CallState.CONNECTING)
                createOffer()
            }
            CallAction.OFFER -> if (matches(control)) receiveOffer(control.payload ?: return)
            CallAction.ANSWER -> if (matches(control)) receiveAnswer(control.payload ?: return)
            CallAction.ICE -> if (matches(control)) receiveIce(control.payload ?: return)
            CallAction.REJECT -> if (matches(control)) closeCall(CallResult.DECLINED, CallEndedBy.CONTACT)
            CallAction.CANCEL -> if (matches(control)) closeCall(CallResult.MISSED, CallEndedBy.CONTACT)
            CallAction.BUSY -> if (matches(control)) closeCall(CallResult.BUSY, CallEndedBy.CONTACT)
            CallAction.END -> if (matches(control)) closeCall(CallResult.COMPLETED, CallEndedBy.CONTACT)
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

    fun disconnect() = closeCall(
        if (state == CallState.RINGING) CallResult.MISSED else CallResult.INTERRUPTED,
        CallEndedBy.SYSTEM
    )

    private fun receiveInvite(id: String) {
        if (state != CallState.IDLE) {
            send(CallControl(id, CallAction.BUSY))
            return
        }
        callId = id
        onHistory(
            CallHistoryEvent(id, CallHistoryStage.STARTED, System.currentTimeMillis(), CallDirection.INCOMING)
        )
        setState(CallState.RINGING)
        if (!showIncomingNotification()) startRinging()
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

    private fun closeCall(result: CallResult, endedBy: CallEndedBy) {
        val previousState = state
        if (previousState == CallState.IDLE && callId == null) return
        val id = callId ?: return
        val endedAt = System.currentTimeMillis()
        state = CallState.IDLE
        callId = null
        startedAt = 0
        muted = false
        speakerOn = false
        stopRinging()
        cancelIncomingNotification()
        closeMedia()
        onHistory(CallHistoryEvent(id, CallHistoryStage.ENDED, endedAt, result = result, endedBy = endedBy))
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
        if (value == CallState.ACTIVE && startedAt == 0L) {
            startedAt = System.currentTimeMillis()
            callId?.let { onHistory(CallHistoryEvent(it, CallHistoryStage.CONNECTED, startedAt)) }
        }
        changed()
    }

    private fun changed() = main.post(onChanged)

    private fun scheduleTimeout(id: String) {
        main.postDelayed({
            if (callId != id) return@postDelayed
            if (state == CallState.RINGING) {
                send(CallControl(id, CallAction.REJECT))
                closeCall(CallResult.MISSED, CallEndedBy.SYSTEM)
            } else if (state == CallState.CALLING) {
                send(CallControl(id, CallAction.CANCEL))
                closeCall(CallResult.NO_ANSWER, CallEndedBy.SYSTEM)
            }
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

    private fun startRinging() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.also {
                    if (Build.VERSION.SDK_INT >= 28) it.isLooping = true
                    it.play()
                }
        }
        startVibration(longArrayOf(0, 700, 700))
    }

    private fun startCallingFeedback() {
        startVibration(longArrayOf(0, 180, 820))
    }

    private fun startVibration(pattern: LongArray) {
        vibrator?.cancel()
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }.also {
            if (Build.VERSION.SDK_INT >= 26) {
                it.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun showIncomingNotification(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = app.getSystemService(NotificationManager::class.java)
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        manager.createNotificationChannel(
            NotificationChannel(CallChannel, "Chamadas recebidas", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 700)
            }
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
            .setFullScreenIntent(openApp, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                "Recusar",
                CallActionReceiver.pendingIntent(app, peerId, CallActionReceiver.Reject)
            )
            .setAutoCancel(false)
            .setOngoing(true)
            .setTimeoutAfter(CallTimeout)
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
        return true
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
                PeerConnection.IceConnectionState.CLOSED -> closeCall(CallResult.INTERRUPTED, CallEndedBy.SYSTEM)
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
        const val CallChannel = "mensageiro_incoming_calls_v3"
        const val IncomingNotification = 3_002
    }
}
