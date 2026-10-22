package zw.co.payport.gateway.channels;

/** A channel switched off from the portal: nothing was sent, no money was taken (503 CHANNEL_UNAVAILABLE). */
public class ChannelUnavailableException extends RuntimeException {
    private final String channel;

    public ChannelUnavailableException(String channel, String message) {
        super(message);
        this.channel = channel;
    }

    public String getChannel() {
        return channel;
    }
}
