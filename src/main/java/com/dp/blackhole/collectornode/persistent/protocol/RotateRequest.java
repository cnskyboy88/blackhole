package com.dp.blackhole.collectornode.persistent.protocol;

import java.nio.ByteBuffer;

import com.dp.blackhole.network.GenUtil;
import com.dp.blackhole.network.NonDelegationTypedWrappable;

public class RotateRequest extends NonDelegationTypedWrappable {
    public String topic;
    public String partitionId;
    public long rollPeriod;
    
    public RotateRequest() {
    }
    
    public RotateRequest(String topic, String partitionId, long rollPeriod) {
        this.topic = topic;
        this.partitionId = partitionId;
        this.rollPeriod = rollPeriod;
    }
    
    @Override
    public int getSize() {
        return GenUtil.getStringSize(topic) + GenUtil.getStringSize(partitionId) + Long.SIZE/8;
    }

    @Override
    public void read(ByteBuffer buffer) {
        topic = GenUtil.readString(buffer);
        partitionId = GenUtil.readString(buffer);
        rollPeriod = buffer.getLong();
    }

    @Override
    public void write(ByteBuffer buffer) {
        GenUtil.writeString(topic, buffer);
        GenUtil.writeString(partitionId, buffer);
        buffer.putLong(rollPeriod);
    }

    @Override
    public int getType() {
        return DataMessageTypeFactory.RotateRequest;
    }

}
