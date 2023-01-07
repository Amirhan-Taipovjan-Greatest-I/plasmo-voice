package su.plo.voice.listeners;

import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import su.plo.voice.PlasmoVoice;
import su.plo.voice.PlasmoVoiceConfig;
import su.plo.voice.common.packets.Packet;
import su.plo.voice.common.packets.tcp.ClientConnectPacket;
import su.plo.voice.common.packets.tcp.ConfigPacket;
import su.plo.voice.common.packets.tcp.PacketTCP;
import su.plo.voice.events.PlayerConfigEvent;
import su.plo.voice.socket.SocketServerUDP;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.UUID;

public class PluginChannelListener implements PluginMessageListener {
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!channel.equals("plasmo:voice")) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(PlasmoVoice.getInstance(), () -> {
            try {
                Packet packet = PacketTCP.read(ByteStreams.newDataInput(bytes));
                if (packet instanceof ClientConnectPacket) {
                    ClientConnectPacket connect = (ClientConnectPacket) packet;
                    PlasmoVoiceConfig config = PlasmoVoice.getInstance().getVoiceConfig();

                    String version = connect.getVersion();
                    int ver = PlasmoVoice.calculateVersion(version);

                    if (ver > PlasmoVoice.version) {
                        player.spigot().sendMessage(new TranslatableComponent("message.plasmo_voice.version_not_supported", PlasmoVoice.rawVersion));
                        return;
                    } else if (ver < PlasmoVoice.minVersion) {
                        player.spigot().sendMessage(new TranslatableComponent("message.plasmo_voice.min_version", PlasmoVoice.rawMinVersion));
                        return;
                    } else if (ver < PlasmoVoice.version) {
                        TextComponent link = new TextComponent(PlasmoVoice.downloadLink);
                        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, PlasmoVoice.downloadLink));

                        player.spigot().sendMessage(new TranslatableComponent("message.plasmo_voice.new_version_available",
                                PlasmoVoice.rawVersion,
                                link));
                    }

                    ConfigPacket configPacket = new ConfigPacket(
                            config.getSampleRate(),
                            new ArrayList<>(config.getDistances()),
                            config.getDefaultDistance(),
                            config.getMaxPriorityDistance(),
                            config.getFadeDivisor(),
                            config.getPriorityFadeDivisor(),
                            config.isDisableVoiceActivation() || !player.hasPermission("voice.activation")
                    );

                    Bukkit.getPluginManager().callEvent(new PlayerConfigEvent(player, configPacket, PlayerConfigEvent.Cause.CONNECT));

                    byte[] pkt = PacketTCP.write(configPacket);
                    player.sendPluginMessage(PlasmoVoice.getInstance(), "plasmo:voice", pkt);
                }
            } catch (IOException | IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
                e.printStackTrace();
            }
        });
    }

    public static void sendToClients(Packet packet, @Nullable Player sender) {
        Bukkit.getScheduler().runTaskAsynchronously(PlasmoVoice.getInstance(), () -> {
            try {
                byte[] pkt = PacketTCP.write(packet);
                Enumeration<UUID> it = SocketServerUDP.clients.keys();
                while (it.hasMoreElements()) {
                    Player player = Bukkit.getPlayer(it.nextElement());
                    if (player == null) continue;

                    if (sender != null) {
                        if (player.canSee(sender)) {
                            player.sendPluginMessage(PlasmoVoice.getInstance(), "plasmo:voice", pkt);
                        }
                    } else {
                        player.sendPluginMessage(PlasmoVoice.getInstance(), "plasmo:voice", pkt);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public static void sendToClients(Packet packet, UUID except, @Nullable Player sender) {
        Bukkit.getScheduler().runTaskAsynchronously(PlasmoVoice.getInstance(), () -> {
            try {
                byte[] pkt = PacketTCP.write(packet);
                Enumeration<UUID> it = SocketServerUDP.clients.keys();
                while (it.hasMoreElements()) {
                    Player player = Bukkit.getPlayer(it.nextElement());
                    if (player == null) continue;

                    if (!player.getUniqueId().equals(except)) {
                        if (sender != null) {
                            if (player.canSee(sender)) {
                                player.sendPluginMessage(PlasmoVoice.getInstance(), "plasmo:voice", pkt);
                            }
                        } else {
                            player.sendPluginMessage(PlasmoVoice.getInstance(), "plasmo:voice", pkt);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
