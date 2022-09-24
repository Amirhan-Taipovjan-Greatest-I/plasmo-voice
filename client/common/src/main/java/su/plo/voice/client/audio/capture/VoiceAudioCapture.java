package su.plo.voice.client.audio.capture;

import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.plo.lib.client.MinecraftClientLib;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.audio.codec.CodecException;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.audio.capture.AudioCapture;
import su.plo.voice.api.client.audio.capture.ClientActivation;
import su.plo.voice.api.client.audio.capture.ClientActivationManager;
import su.plo.voice.api.client.audio.device.AudioDevice;
import su.plo.voice.api.client.audio.device.DeviceManager;
import su.plo.voice.api.client.audio.device.DeviceType;
import su.plo.voice.api.client.audio.device.InputDevice;
import su.plo.voice.api.client.connection.ServerConnection;
import su.plo.voice.api.client.connection.ServerInfo;
import su.plo.voice.api.client.event.audio.capture.AudioCaptureEvent;
import su.plo.voice.api.client.socket.UdpClient;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.api.encryption.EncryptionException;
import su.plo.voice.api.util.AudioUtil;
import su.plo.voice.api.util.Params;
import su.plo.voice.client.audio.filter.StereoToMonoFilter;
import su.plo.voice.client.config.ClientConfig;
import su.plo.voice.proto.data.audio.capture.CaptureInfo;
import su.plo.voice.proto.data.audio.codec.CodecInfo;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import javax.sound.sampled.AudioFormat;
import java.util.Collection;
import java.util.Optional;

public final class VoiceAudioCapture implements AudioCapture {

    private static final Logger LOGGER = LogManager.getLogger();

    private final MinecraftClientLib minecraft;
    private final PlasmoVoiceClient voiceClient;
    private final DeviceManager devices;
    private final ClientActivationManager activations;
    private final ClientConfig config;

    @Setter
    private volatile AudioEncoder monoEncoder;
    @Setter
    private volatile AudioEncoder stereoEncoder;
    @Setter
    private volatile Encryption encryption;

    private Thread thread;

    private long sequenceNumber;

    public VoiceAudioCapture(@NotNull MinecraftClientLib minecraft,
                             @NotNull PlasmoVoiceClient voiceClient,
                             @NotNull ClientConfig config) {
        this.minecraft = minecraft;
        this.voiceClient = voiceClient;
        this.devices = voiceClient.getDeviceManager();
        this.activations = voiceClient.getActivationManager();
        this.config = config;
    }

    @Override
    public Optional<AudioEncoder> getMonoEncoder() {
        return Optional.ofNullable(monoEncoder);
    }

    @Override
    public Optional<AudioEncoder> getStereoEncoder() {
        return Optional.ofNullable(stereoEncoder);
    }

    @Override
    public Optional<Encryption> getEncryption() {
        return Optional.ofNullable(encryption);
    }

    private Optional<InputDevice> getDevice() {
        Collection<AudioDevice> devices = this.devices.getDevices(DeviceType.INPUT);
        return Optional.ofNullable((InputDevice) devices.stream().findFirst().orElse(null));
    }

    @Override
    public void initialize(@NotNull ServerInfo serverInfo) {
        // initialize input device
        AudioFormat format = serverInfo.getVoiceInfo().getFormat(
                config.getVoice().getStereoCapture().value()
        );

        if (!getDevice().isPresent()) {
            try {
                InputDevice device = voiceClient.getDeviceManager().openInputDevice(format, Params.EMPTY);
                devices.replace(null, device);
            } catch (Exception e) {
                LOGGER.error("Failed to open input device", e);
            }
        }

        // initialize encoder
        CaptureInfo capture = serverInfo.getVoiceInfo().getCapture();
        if (capture.getCodec() != null) {
            CodecInfo codec = capture.getCodec();

            Params.Builder params = Params.builder();
            codec.getParams().forEach(params::set);

            this.monoEncoder = voiceClient.getCodecManager().createEncoder(
                    codec.getName(),
                    capture.getSampleRate(),
                    false,
                    serverInfo.getVoiceInfo().getBufferSize(),
                    capture.getMtuSize(),
                    params.build()
            );

            this.stereoEncoder = voiceClient.getCodecManager().createEncoder(
                    codec.getName(),
                    capture.getSampleRate(),
                    true,
                    serverInfo.getVoiceInfo().getBufferSize(),
                    capture.getMtuSize(),
                    params.build()
            );
        }

        // initialize encryption
        if (serverInfo.getEncryption().isPresent()) {
            this.encryption = serverInfo.getEncryption().get();
        }

        LOGGER.info("Audio capture initialized");
    }

    @Override
    public void start() {
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                return;
            }
        }

        this.thread = new Thread(this::run);
        thread.setName("Voice Audio Capture");
        thread.start();
    }

    @Override
    public void stop() {
        thread.interrupt();
    }

    @Override
    public boolean isActive() {
        return thread != null;
    }

    private void run() {
        while (!thread.isInterrupted()) {
            try {
                Optional<InputDevice> device = getDevice();

                if (!device.isPresent()
                        || !device.get().isOpen()
                        || !voiceClient.getServerInfo().isPresent()
                        || !activations.getParentActivation().isPresent()) {
                    Thread.sleep(1_000L);
                    continue;
                }

                device.get().start();
                short[] samples = device.get().read();
                if (samples == null) {
                    Thread.sleep(5L);
                    continue;
                }

                AudioCaptureEvent captureEvent = new AudioCaptureEvent(this, device.get(), samples);
                voiceClient.getEventBus().call(captureEvent);
                if (captureEvent.isCancelled()) continue;

                ClientActivation parentActivation = activations.getParentActivation().get();

                if (captureEvent.isSendEnd() || config.getVoice().getMicrophoneDisabled().value()) {
                    if (parentActivation.isActivated()) {
                        parentActivation.reset();
                        sendVoiceEndPacket(parentActivation);
                    }

                    activations.getActivations().forEach((activation) -> {
                        if (activation.isActivated()) {
                            activation.reset();
                            sendVoiceEndPacket(activation);
                        }
                    });
                    continue;
                }

                ClientActivation.Result result = parentActivation.process(samples);

                EncodedCapture encoded = new EncodedCapture();
                processActivation(device.get(), parentActivation, result, samples, encoded);

                for (ClientActivation activation : activations.getActivations()) {
                    if (activation.isDisabled() || activation.equals(parentActivation)) continue;

                    if (activation.getType() == ClientActivation.Type.INHERIT ||
                            activation.getType() == ClientActivation.Type.VOICE) {
                        processActivation(device.get(), activation, result, samples, encoded);
                    } else {
                        processActivation(device.get(), activation, activation.process(samples), samples, encoded);
                    }

                    if (!activation.isTransitive()) break;
                }
            } catch (InterruptedException ignored) {
                break;
            }
        }

        cleanup();
    }

    private void cleanup() {
        this.sequenceNumber = 0L;
        if (monoEncoder != null) monoEncoder.close();
        if (stereoEncoder != null) stereoEncoder.close();

        Optional<InputDevice> device = getDevice();
        if (device.isPresent() && device.get().isOpen()) {
            device.get().close();
            devices.remove(device.get());
        }

        this.thread = null;
    }

    private void processActivation(@NotNull InputDevice device,
                                   @NotNull ClientActivation activation,
                                   @NotNull ClientActivation.Result result,
                                   short[] samples,
                                   @NotNull EncodedCapture encoded) {
        boolean isStereo = config.getVoice().getStereoCapture().value() && activation.isStereoSupported();

        if (result.isActivated()) {
            if (isStereo && encoded.stereo == null) {
                samples = device.processFilters(samples, (filter) -> (filter instanceof StereoToMonoFilter));
                encoded.stereo = encode(stereoEncoder, samples);
            } else if (!isStereo && encoded.mono == null) {
                samples = device.processFilters(samples);
                encoded.mono = encode(monoEncoder, samples); // todo: change to mono
            }
        }

        byte[] encodedData = isStereo ? encoded.stereo : encoded.mono;

        if (result == ClientActivation.Result.ACTIVATED) {
            sendVoicePacket(activation, isStereo, encodedData);
        } else if (result == ClientActivation.Result.END) {
            sendVoicePacket(activation, isStereo, encodedData);
            sendVoiceEndPacket(activation);
        }
    }

    private byte[] encode(@Nullable AudioEncoder encoder, short[] samples) {
        byte[] encoded;
        if (encoder != null) {
            try {
                encoded = encoder.encode(samples);
            } catch (CodecException e) {
                LOGGER.error("Failed to encode audio data", e);
                return null;
            }
        } else {
            encoded = AudioUtil.shortsToBytes(samples);
        }

        if (encryption != null) {
            try {
                encoded = encryption.encrypt(encoded);
            } catch (EncryptionException e) {
                LOGGER.error("Failed to encrypt audio data", e);
                return null;
            }
        }

        return encoded;
    }

    private void sendVoicePacket(@NotNull ClientActivation activation,
                                 boolean isStereo,
                                 byte[] encoded) {
        if (activation.getTranslation().equals("key.plasmovoice.parent")) return;

        Optional<UdpClient> udpClient = voiceClient.getUdpClientManager().getClient();
        if (!udpClient.isPresent()) return;

        udpClient.get().sendPacket(new PlayerAudioPacket(
                sequenceNumber++,
                encoded,
                activation.getId(),
                (short) activation.getDistance(),
                isStereo
        ));
    }

    private void sendVoiceEndPacket(ClientActivation activation) {
        if (activation.getTranslation().equals("key.plasmovoice.parent")) return;

        if (monoEncoder != null) monoEncoder.reset();
        if (stereoEncoder != null) stereoEncoder.reset();

        Optional<ServerConnection> connection = voiceClient.getServerConnection();
        if (!connection.isPresent()) return;

        connection.get().sendPacket(new PlayerAudioEndPacket(
                sequenceNumber++,
                (short) activation.getDistance()
        ));
    }

    static class EncodedCapture {

        private byte[] mono;
        private byte[] stereo;
    }
}
