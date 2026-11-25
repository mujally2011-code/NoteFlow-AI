package org.noteflow.app.offline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android MediaCodec audio -> mono signed PCM16 @ 16 kHz, for offline speech recognition.
 * This is decoded in a worker, not on the UI thread. Only completed audio files are accepted.
 */
class PcmDecoder {
    private var fraction=0.0
    private var last: Short = 0
    fun resamplePcm16(bytes: ByteArray, channels: Int, sourceRate: Int): ByteArray {
        require(channels in 1..8 && sourceRate in 8000..192000)
        val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames=bytes.size / (channels*2)
        if(frames==0)return ByteArray(0)
        val samples=ShortArray(frames)
        for(i in 0 until frames){var sum=0;for(c in 0 until channels)sum+=input.short.toInt();samples[i]=(sum/channels).toShort()}
        val output=ByteBuffer.allocate((frames*16000L/sourceRate+5).toInt()*2).order(ByteOrder.LITTLE_ENDIAN)
        val step=sourceRate.toDouble()/16000.0
        while(fraction<frames) {
            val position=fraction.toInt()
            val left=if(position>=0) samples[position] else last
            val right=samples[(position+1).coerceAtMost(frames-1)]
            val value=(left+(right-left)*(fraction-position)).toInt().coerceIn(-32768,32767)
            output.putShort(value.toShort());fraction+=step
        }
        fraction-=frames
        last=samples.last()
        return output.array().copyOf(output.position())
    }

    fun decode(file: File, onPcm:(ByteArray)->Unit) {
        require(file.isFile && file.length()>0){"Audio file not found"}
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        try{
            extractor.setDataSource(file.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull{
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            } ?: error("No audio track")
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            val mime=format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
            codec=MediaCodec.createDecoderByType(mime)
            codec.configure(format,null,null,0);codec.start()
            val info=MediaCodec.BufferInfo()
            var inputDone=false;var outputDone=false
            var sampleRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var buffers=0
            while(!outputDone){
                if(!inputDone){
                    val index=codec.dequeueInputBuffer(10000)
                    if(index>=0){
                        val input=codec.getInputBuffer(index) ?: error("Audio input buffer missing")
                        input.clear()
                        val read=extractor.readSampleData(input,0)
                        if(read<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else {codec.queueInputBuffer(index,0,read,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                when(val index=codec.dequeueOutputBuffer(info,10000)){
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{
                        val changed=codec.outputFormat
                        sampleRate=changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels=changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if(changed.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            require(changed.getInteger(MediaFormat.KEY_PCM_ENCODING)==2){"Only PCM16 decoder output is supported"}
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER->{buffers++;require(buffers<500){"Audio decoder stalled"}}
                    else->if(index>=0){
                        buffers=0
                        val output=codec.getOutputBuffer(index) ?: error("Audio output buffer missing")
                        if(info.size>0){
                            output.position(info.offset);output.limit(info.offset+info.size)
                            val pcm=ByteArray(info.size);output.get(pcm)
                            onPcm(resamplePcm16(pcm,channels,sampleRate))
                        }
                        outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0
                        codec.releaseOutputBuffer(index,false)
                    }
                }
            }
        } finally {try{codec?.stop()}catch(_:Exception){};codec?.release();extractor.release()}
    }
}
