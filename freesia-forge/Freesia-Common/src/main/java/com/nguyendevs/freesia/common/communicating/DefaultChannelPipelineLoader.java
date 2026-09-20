package com.nguyendevs.freesia.common.communicating;

import io.netty.channel.Channel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import com.nguyendevs.freesia.common.communicating.codec.MessageDecoder;
import com.nguyendevs.freesia.common.communicating.codec.MessageEncoder;
import org.jetbrains.annotations.NotNull;

public class DefaultChannelPipelineLoader {
    private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    public static void loadDefaultHandlers(@NotNull Channel channel, io.netty.handler.ssl.SslContext sslContext, com.nguyendevs.freesia.common.communicating.FreesiaIpFilterHandler ipFilterHandler) {
        if (ipFilterHandler != null) {
            channel.pipeline().addFirst("firewall", ipFilterHandler);
        }
        if (sslContext != null) {
            channel.pipeline().addLast(sslContext.newHandler(channel.alloc()));
        }
        channel.pipeline()
                .addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_SIZE, 0, 4, 0, 4))
                .addLast(new LengthFieldPrepender(4))
                .addLast(new MessageEncoder())
                .addLast(new MessageDecoder());
    }

}

