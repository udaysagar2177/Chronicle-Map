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

import net.openhft.chronicle.hash.impl.BigSegmentHeader;
import net.openhft.chronicle.hash.impl.LocalLockState;
import net.openhft.chronicle.hash.impl.SegmentHeader;
import net.openhft.chronicle.hash.impl.VanillaChronicleHash;
import net.openhft.chronicle.hash.impl.stage.hash.Chaining;
import net.openhft.chronicle.hash.impl.stage.hash.CheckOnEachPublicOperation;
import net.openhft.chronicle.hash.impl.VanillaChronicleHashHolder;
import net.openhft.chronicle.hash.locks.InterProcessLock;
import net.openhft.chronicle.hash.locks.InterProcessReadWriteUpdateLock;
import net.openhft.lang.collection.DirectBitSet;
import net.openhft.lang.collection.SingleThreadedDirectBitSet;
import net.openhft.lang.io.MultiStoreBytes;
import net.openhft.sg.Stage;
import net.openhft.sg.Staged;
import net.openhft.sg.StageRef;
import org.jetbrains.annotations.NotNull;

import static net.openhft.chronicle.hash.impl.LocalLockState.UNLOCKED;

@Staged
public abstract class SegmentStages implements InterProcessReadWriteUpdateLock {

    @StageRef Chaining chaining;
    @StageRef public VanillaChronicleHashHolder<?, ?, ?> hh;
    @StageRef public CheckOnEachPublicOperation checkOnEachPublicOperation;

    @Stage("TheSegmentIndex") public int segmentIndex = -1;
    
    public void initTheSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
        
    }

    @Stage("SegHeader") long segmentHeaderAddress;
    @Stage("SegHeader") SegmentHeader segmentHeader = null;

    private void initSegHeader() {
        segmentHeaderAddress = hh.h().ms.address() + hh.h().segmentHeaderOffset(segmentIndex);
        segmentHeader = BigSegmentHeader.INSTANCE;
    }

    public long entries() {
        return segmentHeader.size(segmentHeaderAddress);
    }

    public void entries(long size) {
        segmentHeader.size(segmentHeaderAddress, size);
    }

    long nextPosToSearchFrom() {
        return segmentHeader.nextPosToSearchFrom(segmentHeaderAddress);
    }

    public void nextPosToSearchFrom(long nextPosToSearchFrom) {
        segmentHeader.nextPosToSearchFrom(segmentHeaderAddress, nextPosToSearchFrom);
    }

    public long deleted() {
        return segmentHeader.deleted(segmentHeaderAddress);
    }

    public void deleted(long deleted) {
        segmentHeader.deleted(segmentHeaderAddress, deleted);
    }

    public long size() {
        return entries() - deleted();
    }


    @Stage("Locks") public SegmentStages rootContextOnThisSegment = null;
    /**
     * See the ChMap Ops spec, considerations of nested same-thread concurrent contexts.
     * Once context enters the segment, and observes concurrent same-thread context,
     * it sets concurrentSameThreadContexts = true for itself and that concurrent context.
     * This flag is not dropped on exit of one of these contexts, because between calls of
     * this context, nested one could be initialized, does some changes that break our thread-local
     * assumptions _and exit_, that is why on exit concurrent context should remain "dirty".
     */
    @Stage("Locks") public boolean concurrentSameThreadContexts;

    @Stage("Locks") public int latestSameThreadSegmentModCount;
    @Stage("Locks") public int contextModCount;

    @Stage("Locks")
    public void incrementModCount() {
        contextModCount = rootContextOnThisSegment.latestSameThreadSegmentModCount =
                rootContextOnThisSegment.latestSameThreadSegmentModCount + 1;
    }

    // chain
    @Stage("Locks") SegmentStages nextNode;

    @Stage("Locks") LocalLockState localLockState;
    @Stage("Locks") int totalReadLockCount;
    @Stage("Locks") int totalUpdateLockCount;
    @Stage("Locks") int totalWriteLockCount;

    public abstract boolean locksInit();

    void initLocks() {
        localLockState = UNLOCKED;
        int indexOfThisContext = chaining.indexInContextChain;
        for (int i = indexOfThisContext - 1; i >= 0; i--) {
            if (tryFindInitLocksOfThisSegment(this, i))
                return;
        }
        for (int i = indexOfThisContext + 1, size = chaining.contextChain.size(); i < size; i++) {
            if (tryFindInitLocksOfThisSegment(this, i))
                return;
        }
        rootContextOnThisSegment = this;
        concurrentSameThreadContexts = false;
        latestSameThreadSegmentModCount = 0;
        contextModCount = 0;
        nextNode = null;
        totalReadLockCount = 0;
        totalUpdateLockCount = 0;
        totalWriteLockCount = 0;
    }

    boolean tryFindInitLocksOfThisSegment(Object thisContext, int index) {
        SegmentStages c = chaining.contextAtIndexInChain(index);
        if (c.segmentHeader != null && c.segmentHeaderAddress == segmentHeaderAddress &&
                c.rootContextOnThisSegment != null) {
            //TODO implement nested contexts. Hardest part - bodies of Read/Update/WriteLock classes
            throw new IllegalStateException("Nested context not implemented yet");
        } else {
            return false;
        }
    }

    void closeLocks() {
        // TODO should throw ISE on attempt to close root context when children not closed
        if (rootContextOnThisSegment == this) {
            closeRootLocks();
        } else {
            closeNestedLocks();
        }
        localLockState = null;
        rootContextOnThisSegment = null;
    }

    @Stage("Locks")
    private void closeNestedLocks() {
        unlinkFromSegmentContextsChain();
        
        switch (localLockState) {
            case UNLOCKED:
                break;
            case READ_LOCKED:
                int newTotalReadLockCount = this.rootContextOnThisSegment.totalReadLockCount -= 1;
                if (newTotalReadLockCount == 0) {
                    if (this.rootContextOnThisSegment.totalUpdateLockCount == 0 &&
                            this.rootContextOnThisSegment.totalWriteLockCount == 0) {
                        segmentHeader.readUnlock(segmentHeaderAddress);
                    }
                } else if (newTotalReadLockCount < 0) {
                    throw new IllegalStateException("read underflow");
                }
                break;
            case UPDATE_LOCKED:
                int newTotalUpdateLockCount =
                        this.rootContextOnThisSegment.totalUpdateLockCount -= 1;
                if (newTotalUpdateLockCount == 0) {
                    if (this.rootContextOnThisSegment.totalWriteLockCount == 0) {
                        if (this.rootContextOnThisSegment.totalReadLockCount == 0) {
                            segmentHeader.updateUnlock(segmentHeaderAddress);
                        } else {
                            segmentHeader.downgradeUpdateToReadLock(segmentHeaderAddress);
                        }
                    }
                } else if (newTotalUpdateLockCount < 0) {
                    throw new IllegalStateException("update underflow");
                }
                break;
            case WRITE_LOCKED:
                int newTotalWriteLockCount = this.rootContextOnThisSegment.totalWriteLockCount -= 1;
                if (newTotalWriteLockCount == 0) {
                    if (this.rootContextOnThisSegment.totalUpdateLockCount > 0) {
                        segmentHeader.downgradeWriteToUpdateLock(segmentHeaderAddress);
                    } else {
                        if (this.rootContextOnThisSegment.totalReadLockCount > 0) {
                            segmentHeader.downgradeWriteToReadLock(segmentHeaderAddress);
                        } else {
                            segmentHeader.writeUnlock(segmentHeaderAddress);
                        }
                    }
                }
                break;
        }
    }

    @Stage("Locks")
    private void unlinkFromSegmentContextsChain() {
        SegmentStages prevContext = this.rootContextOnThisSegment;
        while (true) {
            assert prevContext.nextNode != null;
            if (prevContext.nextNode == this)
                break;
            prevContext = prevContext.nextNode;
        }
        assert nextNode == null;
        prevContext.nextNode = null;
    }

    @Stage("Locks")
    private void closeRootLocks() {
        assert nextNode == null;
        
        
        switch (localLockState) {
            case UNLOCKED:
                return;
            case READ_LOCKED:
                segmentHeader.readUnlock(segmentHeaderAddress);
                return;
            case UPDATE_LOCKED:
                segmentHeader.updateUnlock(segmentHeaderAddress);
                return;
            case WRITE_LOCKED:
                segmentHeader.writeUnlock(segmentHeaderAddress);
        }
    }

    @Stage("Locks")
    public void setLocalLockState(LocalLockState newState) {
        localLockState = newState;
    }

    // TODO their isHeldByCurrentThread should be _context_ local
    // i. e. if outer same-thread context hold the lock, isHeld() should be false, but
    // lock() op could be shallow
    @StageRef public ReadLock innerReadLock;
    @StageRef public UpdateLock innerUpdateLock;
    @StageRef public WriteLock innerWriteLock;


    @NotNull
    @Override
    public InterProcessLock readLock() {
        checkOnEachPublicOperation.checkOnEachPublicOperation();
        return innerReadLock;
    }

    @NotNull
    @Override
    public InterProcessLock updateLock() {
        checkOnEachPublicOperation.checkOnEachPublicOperation();
        return innerUpdateLock;
    }

    @NotNull
    @Override
    public InterProcessLock writeLock() {
        checkOnEachPublicOperation.checkOnEachPublicOperation();
        return innerWriteLock;
    }

    
    @StageRef public HashLookup hashLookup;
    
    @Stage("Segment") MultiStoreBytes freeListBytes = new MultiStoreBytes();
    @Stage("Segment") public SingleThreadedDirectBitSet freeList = new SingleThreadedDirectBitSet();
    @Stage("Segment") long entrySpaceOffset = 0;
    
    boolean segmentInit() {
        return entrySpaceOffset > 0;
    }

    void initSegment() {
        VanillaChronicleHash<?, ?, ?, ?, ?> h = hh.h();
        long hashLookupOffset = h.segmentOffset(segmentIndex);
        long freeListOffset = hashLookupOffset + h.segmentHashLookupOuterSize;
        freeListBytes.storePositionAndSize(h.ms, freeListOffset, h.segmentFreeListInnerSize);
        freeList.reuse(freeListBytes);
        entrySpaceOffset = freeListOffset + h.segmentFreeListOuterSize +
                h.segmentEntrySpaceInnerOffset;
    }
    
    void closeSegment() {
        entrySpaceOffset = 0;
    }

    //TODO refactor/optimize
    public long alloc(int chunks) {
        VanillaChronicleHash<?, ?, ?, ?, ?> h = hh.h();
        if (chunks > h.maxChunksPerEntry)
            throw new IllegalArgumentException("Entry is too large: requires " + chunks +
                    " entry size chucks, " + h.maxChunksPerEntry + " is maximum.");
        long ret = freeList.setNextNContinuousClearBits(nextPosToSearchFrom(), chunks);
        if (ret == DirectBitSet.NOT_FOUND || ret + chunks > h.actualChunksPerSegment) {
            if (ret != DirectBitSet.NOT_FOUND &&
                    ret + chunks > h.actualChunksPerSegment && ret < h.actualChunksPerSegment)
                freeList.clear(ret, h.actualChunksPerSegment);
            ret = freeList.setNextNContinuousClearBits(0L, chunks);
            if (ret == DirectBitSet.NOT_FOUND || ret + chunks > h.actualChunksPerSegment) {
                if (ret != DirectBitSet.NOT_FOUND &&
                        ret + chunks > h.actualChunksPerSegment &&
                        ret < h.actualChunksPerSegment)
                    freeList.clear(ret, h.actualChunksPerSegment);
                if (chunks == 1) {
                    throw new IllegalStateException(
                            "Segment is full, no free entries found");
                } else {
                    throw new IllegalStateException(
                            "Segment is full or has no ranges of " + chunks
                                    + " continuous free chunks"
                    );
                }
            }
            updateNextPosToSearchFrom(ret, chunks);
        } else {
            // if bit at nextPosToSearchFrom is clear, it was skipped because
            // more than 1 chunk was requested. Don't move nextPosToSearchFrom
            // in this case. chunks == 1 clause is just a fast path.
            if (chunks == 1 || freeList.isSet(nextPosToSearchFrom())) {
                updateNextPosToSearchFrom(ret, chunks);
            }
        }
        return ret;
    }

    public void free(long fromPos, int chunks) {
        freeList.clear(fromPos, fromPos + chunks);
        if (fromPos < nextPosToSearchFrom())
            nextPosToSearchFrom(fromPos);
    }

    public void updateNextPosToSearchFrom(long allocated, int chunks) {
        long nextPosToSearchFrom = allocated + chunks;
        if (nextPosToSearchFrom >= hh.h().actualChunksPerSegment)
            nextPosToSearchFrom = 0L;
        nextPosToSearchFrom(nextPosToSearchFrom);
    }

    public void clearSegment() {
        innerWriteLock.lock();
        hashLookup.clearHashLookup();
        freeList.clear();
        nextPosToSearchFrom(0L);
        entries(0L);
    }
    
    public void clear() {
        clearSegment();
    }
}
