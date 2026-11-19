package com.jarvis.assistant

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import org.webrtc.*

class RealtimeVoiceClient(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onEvent: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val httpClient = OkHttpClient()

    companion object {
        private const val REALTIME_TOKEN_URL =
            "https://4rnk558awb.execute-api.ap-southeast-2.amazonaws.com/prod/realtime-token"
    }

    fun fetchRealtimeToken(cognitoAccessToken: String, callback: (String?) -> Unit) {
        val request = Request.Builder()
            .url(REALTIME_TOKEN_URL)
            .post(RequestBody.create(null, ByteArray(0)))
            .header("Authorization", "Bearer $cognitoAccessToken")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onStatus("Token network error: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string() ?: ""

                    if (!it.isSuccessful) {
                        onStatus("Token HTTP ${it.code}: ${responseBody.take(300)}")
                        callback(null)
                        return
                    }

                    val value = JSONObject(responseBody.ifBlank { "{}" })
                        .optString("value", "")

                    if (value.isBlank()) {
                        onStatus("Token response missing ephemeral token")
                        callback(null)
                        return
                    }

                    callback(value)
                }
            }
        })
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var pendingOfferCallback: ((String?) -> Unit)? = null
    private var localDescriptionReady = false

    private fun finishOffer(pc: PeerConnection?) {
        val callback = pendingOfferCallback ?: return
        pendingOfferCallback = null
        val sdp = pc?.localDescription?.description
        if (sdp.isNullOrBlank()) {
            onStatus("Final local SDP is empty")
            callback(null)
        } else {
            onStatus("ICE gathering complete; sending final SDP")
            callback(sdp)
        }
    }

    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        val factory = peerConnectionFactory ?: return
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("jarvis_microphone", audioSource)

        onStatus("Realtime microphone ready")
    }


    fun createPeerConnection() {
        val factory = peerConnectionFactory ?: run {
            onStatus("WebRTC factory not initialized")
            return
        }

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                onEvent("Signaling: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onEvent("ICE: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                onEvent("ICE Gathering: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE && localDescriptionReady) {
                    finishOffer(peerConnection)
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                onEvent("Remote audio track received")
            }
        }

        peerConnection = factory.createPeerConnection(rtcConfig, observer)
        if (peerConnection == null) {
            onStatus("Peer connection creation failed")
            return
        }

        audioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("jarvis-audio"))
        }

        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            )
        )

        val dcInit = DataChannel.Init()
        dataChannel = peerConnection?.createDataChannel("oai-events", dcInit)

        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                onEvent("Data channel: ${dataChannel?.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onEvent(String(bytes, Charsets.UTF_8))
            }
        })

        onStatus("WebRTC peer connection ready")
    }


    fun createOffer(callback: (String?) -> Unit) {
        val pc = peerConnection ?: run {
            onStatus("Peer connection not ready")
            callback(null)
            return
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription) {
                pendingOfferCallback = callback
                localDescriptionReady = false
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onSetSuccess() {
                        localDescriptionReady = true
                        if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                            finishOffer(pc)
                        }
                    }
                    override fun onCreateFailure(error: String) {
                        pendingOfferCallback = null
                        onStatus("Local SDP setup failed: $error")
                        callback(null)
                    }
                    override fun onSetFailure(error: String) {
                        pendingOfferCallback = null
                        onStatus("Local SDP setup failed: $error")
                        callback(null)
                    }
                }, offer)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                pendingOfferCallback = null
                onStatus("SDP offer failed: $error")
                callback(null)
            }
            override fun onSetFailure(error: String) {}
        }, constraints)
    }


    fun connectToOpenAI(
        ephemeralToken: String,
        offerSdp: String,
        callback: (Boolean) -> Unit
    ) {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/realtime/calls")
            .post("application/sdp".toMediaType().let { offerSdp.toRequestBody(it) })
            .header("Authorization", "Bearer $ephemeralToken")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onStatus("Realtime connection network error")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onStatus("Realtime connection failed: ${it.code}")
                        callback(false)
                        return
                    }

                    val answerSdp = it.body?.string()
                    if (answerSdp.isNullOrBlank()) {
                        onStatus("Empty SDP answer")
                        callback(false)
                        return
                    }

                    val pc = peerConnection ?: run {
                        onStatus("Peer connection unavailable")
                        callback(false)
                        return
                    }

                    val answer = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        answerSdp
                    )

                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription) {}
                        override fun onSetSuccess() {
                            onStatus("JARVIS Realtime connected")
                            callback(true)
                        }
                        override fun onCreateFailure(error: String) {}
                        override fun onSetFailure(error: String) {
                            onStatus("Remote SDP setup failed: $error")
                            callback(false)
                        }
                    }, answer)
                }
            }
        })
    }


    fun sendEvent(json: String): Boolean {
        val channel = dataChannel ?: run {
            onStatus("Data channel unavailable")
            return false
        }

        if (channel.state() != DataChannel.State.OPEN) {
            onStatus("Data channel not open")
            return false
        }

        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)),
            false
        )

        val sent = channel.send(buffer)
        if (!sent) onStatus("Event send failed")
        return sent
    }

    fun stop() {
        dataChannel?.close()
        audioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()

        dataChannel = null
        audioTrack = null
        audioSource = null
        peerConnection = null
        peerConnectionFactory = null

    }
}
