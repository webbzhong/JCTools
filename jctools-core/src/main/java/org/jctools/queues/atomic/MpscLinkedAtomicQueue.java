/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.queues.atomic;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueue.Supplier;
import org.jctools.queues.MessagePassingQueueUtil;
import org.jctools.queues.QueueProgressIndicators;
import org.jctools.queues.IndexedQueueSizeUtil;
import static org.jctools.queues.atomic.AtomicQueueUtil.*;

/**
 * NOTE: This class was automatically generated by org.jctools.queues.atomic.JavaParsingAtomicLinkedQueueGenerator
 * which can found in the jctools-build module. The original source file is MpscLinkedQueue.java.
 *
 * This is a Java port of the MPSC algorithm as presented
 * <a href="http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue"> on
 * 1024 Cores</a> by D. Vyukov. The original has been adapted to Java and it's quirks with regards to memory
 * model and layout:
 * <ol>
 * <li>Use inheritance to ensure no false sharing occurs between producer/consumer node reference fields.
 * <li>Use XCHG functionality to the best of the JDK ability (see differences in JDK7/8 impls).
 * <li>Conform to {@link java.util.Queue} contract on poll. The original semantics are available via relaxedPoll.
 * </ol>
 * The queue is initialized with a stub node which is set to both the producer and consumer node references.
 * From this point follow the notes on offer/poll.
 */
public class MpscLinkedAtomicQueue<E> extends BaseLinkedAtomicQueue<E> {

    public MpscLinkedAtomicQueue() {
        LinkedQueueAtomicNode<E> node = newNode();
        spConsumerNode(node);
        xchgProducerNode(node);
    }

    /**
     * {@inheritDoc} <br>
     * <p>
     * IMPLEMENTATION NOTES:<br>
     * Offer is allowed from multiple threads.<br>
     * Offer allocates a new node and:
     * <ol>
     * <li>Swaps it atomically with current producer node (only one producer 'wins')
     * <li>Sets the new node as the node following from the swapped producer node
     * </ol>
     * This works because each producer is guaranteed to 'plant' a new node and link the old node. No 2
     * producers can get the same producer node as part of XCHG guarantee.
     *
     * @see MessagePassingQueue#offer(Object)
     * @see java.util.Queue#offer(java.lang.Object)
     */
    @Override
    public boolean offer(final E e) {
        if (null == e) {
            throw new NullPointerException();
        }
        final LinkedQueueAtomicNode<E> nextNode = newNode(e);
        final LinkedQueueAtomicNode<E> prevProducerNode = xchgProducerNode(nextNode);
        // Should a producer thread get interrupted here the chain WILL be broken until that thread is resumed
        // and completes the store in prev.next. This is a "bubble".
        prevProducerNode.soNext(nextNode);
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method is only safe to call from the (single) consumer thread, and is subject to best effort when racing
     * with producers. This method is potentially blocking when "bubble"s in the queue are visible.
     */
    @Override
    public boolean remove(Object o) {
        if (null == o) {
            // Null elements are not permitted, so null will never be removed.
            return false;
        }
        final LinkedQueueAtomicNode<E> originalConsumerNode = lpConsumerNode();
        LinkedQueueAtomicNode<E> prevConsumerNode = originalConsumerNode;
        LinkedQueueAtomicNode<E> currConsumerNode = getNextConsumerNode(originalConsumerNode);
        while (currConsumerNode != null) {
            if (o.equals(currConsumerNode.lpValue())) {
                LinkedQueueAtomicNode<E> nextNode = getNextConsumerNode(currConsumerNode);
                // e.g.: consumerNode -> node0 -> node1(o==v) -> node2 ... => consumerNode -> node0 -> node2
                if (nextNode != null) {
                    // We are removing an interior node.
                    prevConsumerNode.soNext(nextNode);
                } else // This case reflects: prevConsumerNode != originalConsumerNode && nextNode == null
                // At rest, this would be the producerNode, but we must contend with racing. Changes to subclassed
                // queues need to consider remove() when implementing offer().
                {
                    // producerNode is currConsumerNode, try to atomically update the reference to move it to the
                    // previous node.
                    prevConsumerNode.soNext(null);
                    if (!casProducerNode(currConsumerNode, prevConsumerNode)) {
                        // If the producer(s) have offered more items we need to remove the currConsumerNode link.
                        nextNode = spinWaitForNextNode(currConsumerNode);
                        prevConsumerNode.soNext(nextNode);
                    }
                }
                // Avoid GC nepotism because we are discarding the current node.
                currConsumerNode.soNext(null);
                currConsumerNode.spValue(null);
                return true;
            }
            prevConsumerNode = currConsumerNode;
            currConsumerNode = getNextConsumerNode(currConsumerNode);
        }
        return false;
    }

    @Override
    public int fill(Supplier<E> s) {
        return MessagePassingQueueUtil.fillUnbounded(this, s);
    }

    @Override
    public int fill(Supplier<E> s, int limit) {
        if (null == s)
            throw new IllegalArgumentException("supplier is null");
        if (limit < 0)
            throw new IllegalArgumentException("limit is negative:" + limit);
        if (limit == 0)
            return 0;
        LinkedQueueAtomicNode<E> tail = newNode(s.get());
        final LinkedQueueAtomicNode<E> head = tail;
        for (int i = 1; i < limit; i++) {
            final LinkedQueueAtomicNode<E> temp = newNode(s.get());
            tail.soNext(temp);
            tail = temp;
        }
        final LinkedQueueAtomicNode<E> oldPNode = xchgProducerNode(tail);
        oldPNode.soNext(head);
        return limit;
    }

    @Override
    public void fill(Supplier<E> s, WaitStrategy wait, ExitCondition exit) {
        MessagePassingQueueUtil.fill(this, s, wait, exit);
    }

    private LinkedQueueAtomicNode<E> getNextConsumerNode(LinkedQueueAtomicNode<E> currConsumerNode) {
        LinkedQueueAtomicNode<E> nextNode = currConsumerNode.lvNext();
        if (nextNode == null && currConsumerNode != lvProducerNode()) {
            nextNode = spinWaitForNextNode(currConsumerNode);
        }
        return nextNode;
    }
}
