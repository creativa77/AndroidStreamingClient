package com.c77.rtpmediaplayer.lib.rtp;

import android.media.MediaFormat;

import com.biasedbit.efflux.packet.DataPacket;
import com.c77.rtpmediaplayer.lib.BufferedSample;
import com.c77.rtpmediaplayer.lib.RtpPlayerException;
import com.c77.rtpmediaplayer.lib.video.Decoder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.buffer.ChannelBuffer;

import java.nio.ByteBuffer;

/**
 * Created by julian on 12/12/14.
 */
public class RtpMediaExtractor {
    private static Log log = LogFactory.getLog(RtpMediaExtractor.class);

    public static final String CSD_0 = "csd-0";
    public static final String CSD_1 = "csd-1";
    public static final String DURATION_US = "durationUs";


    // Extractor settings
    //   Whether to use Byte Stream Format (H.264 spec., annex B)
    //   (prepends the byte stream 0x00000001 to each NAL unit)
    private boolean useByteStreamFormat = true;

    private final byte[] byteStreamStartCodePrefix = {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01};

    private final Decoder decoder;

    private boolean currentFrameHasError = false;
    private BufferedSample currentFrame;

    public RtpMediaExtractor(Decoder decoder) {
        this.decoder = decoder;
    }

    private void startSTAPAFrame(DataPacket packet) {
        // This frame type includes a series of concatenated NAL units, each preceded
        // by a 16-bit size field

        // We'll use the reader index in this parsing routine
        ChannelBuffer buffer = packet.getData();
        // Discard the first byte (RTP getPacket type / nalType came from there)
        try {
            buffer.readByte();
        } catch (IndexOutOfBoundsException e) {
            log.error("jboss AbstractChannelBuffer throws exception when trying to read byte", e);
        }

        while (buffer.readable()) {
            // NAL Unit Size
            short nalUnitSize = buffer.readShort();

            // NAL Unit Data (of the size read above)
            byte[] nalUnitData = new byte[nalUnitSize];
            buffer.readBytes(nalUnitData);

            // Create and send the buffer upstream for processing
            try {
                startFrame(packet.getTimestamp());
            } catch (Exception e) {
                log.error("Error while trying to start frame", e);
            }

            if (currentFrame != null) {
                if (useByteStreamFormat) {
                    currentFrame.getBuffer().put(byteStreamStartCodePrefix);
                }
                currentFrame.getBuffer().put(nalUnitData);
                sendFrame();
            }
        }
    }

    private void startAndSendFrame(DataPacket packet) {
        try {
            startFrame(packet.getTimestamp());
        } catch (Exception e) {
            log.error("Error while trying to start frame", e);
        }
        if (currentFrame != null) {

            if (useByteStreamFormat) {
                currentFrame.getBuffer().put(byteStreamStartCodePrefix);
            }
            currentFrame.getBuffer().put(packet.getData().toByteBuffer());
            sendFrame();
        }
    }

    private void startAndSendFragmentedFrame(DataPacketWithNalType packet) {
        // Do we have a clean start of a frame?
        if (packet.isStart()) {
            try {
                startFrame(packet.getTimestamp());
            } catch (Exception e) {
                log.error("Error while trying to start frame", e);
            }

            if (currentFrame != null) {
                // Add stream header
                if (useByteStreamFormat) {
                    currentFrame.getBuffer().put(byteStreamStartCodePrefix);
                }

                // Re-create the H.264 NAL header from the FU-A header
                // Excerpt from the spec:
                    /* "The NAL unit type octet of the fragmented
                    NAL unit is not included as such in the fragmentation unit payload,
                    but rather the information of the NAL unit type octet of the
                    fragmented NAL unit is conveyed in F and NRI fields of the FU
                    indicator octet of the fragmentation unit and in the type field of
                    the FU header"  */
                byte reconstructedNalTypeOctet = (byte) (packet.fuNalType | packet.nalFBits | packet.nalNriBits);
                currentFrame.getBuffer().put(reconstructedNalTypeOctet);
            }
        }

        // if we don't have a buffer here, it means that we skipped the start getPacket for this
        // NAL unit, so we can't do anything other than discard everything else
        if (currentFrame != null) {
            currentFrame.getBuffer().put(packet.getData().toByteBuffer(2, packet.getDataSize() - 2));

            if (packet.isEnd()) {
                try {
                    sendFrame();
                } catch (Throwable t) {
                    log.error("Error sending frame.", t);
                }
            }
        }
    }

    private void startFrame(long rtpTimestamp) throws Exception {
        // Reset error bit
        currentFrameHasError = false;

        // Deal with potentially non-returned buffer due to error
        if (currentFrame != null) {
            currentFrame.getBuffer().clear();
            // Otherwise, get a fresh buffer from the codec
        } else {
            try {
                // Get buffer from decoder
                currentFrame = decoder.getSampleBuffer();
                currentFrame.getBuffer().clear();
            } catch (RtpPlayerException e) {
                // TODO: Proper error handling
                currentFrameHasError = true;
            }
        }

        if (!currentFrameHasError) {
            // Set the sample timestamp
            currentFrame.setRtpTimestamp(rtpTimestamp);
        }
    }

    private void sendFrame() {
        currentFrame.setSampleSize(currentFrame.getBuffer().position());
        currentFrame.getBuffer().flip();
        try {
            decoder.decodeFrame(currentFrame);
        } catch (Exception e) {
            log.error("Error sending frame", e);
        }

        // Always make currentFrameEntry null to indicate we have returned the buffer to the codec
        currentFrame = null;
    }

    public boolean isUseByteStreamFormat() {
        return useByteStreamFormat;
    }

    public void setUseByteStreamFormat(boolean useByteStreamFormat) {
        this.useByteStreamFormat = useByteStreamFormat;
    }

    // Think how to get CSD-0/CSD-1 codec-specific data chunks
    public MediaFormat getMediaFormat() {
        String mimeType = "video/avc";
        int width = 640;
        int height = 480;

        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        /*
        // the one got from internet
        byte[] header_sps = { 0, 0, 0, 1, // header
                0x67, 0x42, 0x00, 0x1f, (byte)0xe9, 0x01, 0x68, 0x7b, (byte) 0x20 }; // sps
        byte[] header_pps = { 0, 0, 0, 1, // header
                0x68, (byte)0xce, 0x06, (byte)0xf2 }; // pps

        // the one got from libstreaming at HQ
        byte[] header_sps = { 0, 0, 0, 1, // header
                0x67, 0x42, (byte)0x80, 0x14, (byte)0xe4, 0x40, (byte)0xa0, (byte)0xfd, 0x00, (byte)0xda, 0x14, 0x26, (byte)0xa0}; // sps
        byte[] header_pps = { 0, 0, 0, 1, // header
                0x68, (byte)0xce, 0x38, (byte)0x80 }; // pps


        // the one got from libstreaming at home
        byte[] header_sps = { 0, 0, 0, 1, // header
                0x67, 0x42, (byte) 0xc0, 0x1e, (byte) 0xe9, 0x01, 0x40, 0x7b, 0x40, 0x3c, 0x22, 0x11, (byte) 0xa8}; // sps
        byte[] header_pps = { 0, 0, 0, 1, // header
                0x68, (byte) 0xce, 0x06, (byte) 0xe2}; // pps
         */

        // from avconv, when streaming sample.h264.mp4 from disk
        byte[] header_sps = {0, 0, 0, 1, // header
                0x67, 0x64, (byte) 0x00, 0x1e, (byte) 0xac, (byte) 0xd9, 0x40, (byte) 0xa0, 0x3d,
                (byte) 0xa1, 0x00, 0x00, (byte) 0x03, 0x00, 0x01, 0x00, 0x00, 0x03, 0x00, 0x3C, 0x0F, 0x16, 0x2D, (byte) 0x96}; // sps
        byte[] header_pps = {0, 0, 0, 1, // header
                0x68, (byte) 0xeb, (byte) 0xec, (byte) 0xb2, 0x2C}; // pps


        format.setByteBuffer(CSD_0, ByteBuffer.wrap(header_sps));
        format.setByteBuffer(CSD_1, ByteBuffer.wrap(header_pps));

        //format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
        format.setInteger(DURATION_US, 12600000);

        return format;
    }

    public void sendPacket(DataPacketWithNalType packet) {
        switch (packet.nalType()) {
            case FULL:
                startAndSendFrame(packet.getPacket());
                break;
            case NOT_FULL:
                startAndSendFragmentedFrame(packet);
                break;
            case STAPA:
                startSTAPAFrame(packet.getPacket());
                break;
        }
    }
}
