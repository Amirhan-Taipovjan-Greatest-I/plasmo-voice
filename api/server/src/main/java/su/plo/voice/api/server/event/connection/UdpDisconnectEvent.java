package su.plo.voice.api.server.event.connection;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import su.plo.voice.api.event.Event;
import su.plo.voice.api.server.socket.UdpConnection;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This event is fired when the player is disconnected from the UDP server
 * and removed from {@link su.plo.voice.api.server.connection.ConnectionManager}
 */
public final class UdpDisconnectEvent implements Event {

    @Getter
    private final UdpConnection connection;

    public UdpDisconnectEvent(@NotNull UdpConnection connection) {
        checkNotNull(connection, "connection cannot be null");
        this.connection = connection;
    }
}
