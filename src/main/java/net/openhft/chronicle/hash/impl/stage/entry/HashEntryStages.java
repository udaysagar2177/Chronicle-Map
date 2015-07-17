/*
 * Copyright 2015 Higher Frequency Trading
 *
 *  http://www.higherfrequencytrading.com
 *  
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.openhft.chronicle.hash.impl.stage.entry;

import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.bytes.NativeBytesStore;
import net.openhft.chronicle.hash.Data;
import net.openhft.chronicle.hash.HashEntry;
import net.openhft.chronicle.hash.impl.VanillaChronicleHashHolder;
import net.openhft.chronicle.hash.impl.stage.data.bytes.EntryKeyBytesData;
import net.openhft.chronicle.hash.impl.stage.hash.CheckOnEachPublicOperation;
import net.openhft.lang.io.Bytes;
import net.openhft.sg.Stage;
import net.openhft.sg.StageRef;
import net.openhft.sg.Staged;
import org.jetbrains.annotations.NotNull;


@Staged
public abstract class HashEntryStages<K> implements HashEntry<K> {

    @StageRef VanillaChronicleHashHolder<?, ?, ?> hh;
    @StageRef public SegmentStages s;
    @StageRef public CheckOnEachPublicOperation checkOnEachPublicOperation;
    @StageRef public HashLookupPos hlp;
    
    public final Bytes entryBytes = hh.h().ms.bytes();
    public final BytesStore entryBS =
            new NativeBytesStore<>(entryBytes.address(), entryBytes.capacity(), null, false);

    public long pos = -1;

    public void initPos(long pos) {
        this.pos = pos;
    }

    public abstract void closePos();

    @Stage("EntryOffset") public long keySizeOffset = -1;

    public void initEntryOffset() {
        keySizeOffset = s.entrySpaceOffset + pos * hh.h().chunkSize;
        entryBytes.limit(entryBytes.capacity());
    }

    public long keySize = -1;

    public void initKeySize(long keySize) {
        this.keySize = keySize;
    }

    public long keyOffset = -1;

    public void initKeyOffset(long keyOffset) {
        this.keyOffset = keyOffset;
    }

    public void readExistingEntry(long pos) {
        initPos(pos);
        entryBytes.position(keySizeOffset);
        initKeySize(hh.h().keySizeMarshaller.readSize(entryBytes));
        initKeyOffset(entryBytes.position());
    }

    public void writeNewEntry(long pos, Data<?> key) {
        initPos(pos);
        initKeySize(key.size());
        entryBytes.position(keySizeOffset);
        hh.h().keySizeMarshaller.writeSize(entryBytes, keySize);
        initKeyOffset(entryBytes.position());
        key.writeTo(entryBS, keyOffset);
    }

    public void copyExistingEntry(long newPos, long bytesToCopy) {
        long oldKeySizeOffset = keySizeOffset;
        long oldKeyOffset = keyOffset;
        initPos(newPos);
        initKeyOffset(keySizeOffset + (oldKeyOffset - oldKeySizeOffset));
        entryBS.write(keySizeOffset, entryBS, oldKeySizeOffset, bytesToCopy);
    }

    public long keyEnd() {
        return keyOffset + keySize;
    }

    protected long entryEnd() {
        return keyEnd();
    }

    long entrySize() {
        return entryEnd() - keySizeOffset;
    }

    @StageRef EntryKeyBytesData<K> entryKey;

    @NotNull
    @Override
    public Data<K> key() {
        checkOnEachPublicOperation.checkOnEachPublicOperation();
        return entryKey;
    }

    @Stage("TheEntrySizeInChunks") public int entrySizeInChunks = 0;

    void initTheEntrySizeInChunks() {
        entrySizeInChunks = hh.h().inChunks(entrySize());
    }

    public void initTheEntrySizeInChunks(int actuallyUsedChunks) {
        entrySizeInChunks = actuallyUsedChunks;
    }
    
    public void innerRemoveEntryExceptHashLookupUpdate() {
        s.free(pos, entrySizeInChunks);
        s.entries(s.entries() - 1L);
        s.incrementModCount();
    }
}
