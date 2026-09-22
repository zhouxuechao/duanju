package com.yourapp.drama.provider.audio;

import com.yourapp.drama.model.voice.VoiceGenerator;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MockVoiceGenerator implements VoiceGenerator {
    @Override public VoiceResult generate(VoiceRequest request){
        if(request.spokenText()==null||request.spokenText().isBlank())throw new IllegalArgumentException("spokenText 不能为空");
        double duration=Math.max(1,Math.min(10,request.spokenText().codePointCount(0,request.spokenText().length())*.24));
        return new VoiceResult("MOCK","mock-silence","mock-audio-"+UUID.randomUUID(),null,wav(duration),"audio/wav",duration,true);
    }
    private byte[] wav(double seconds){int rate=16000,bytes=(int)Math.ceil(seconds*rate)*2;ByteBuffer b=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+bytes).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);return b.array();}
}
