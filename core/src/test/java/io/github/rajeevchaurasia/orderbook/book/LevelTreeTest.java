package io.github.rajeevchaurasia.orderbook.book;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential test of the intrusive AVL against a TreeMap reference under
 * random insert/remove churn, with full structural validation (ordering,
 * parent pointers, AVL balance) after every mutation batch.
 */
class LevelTreeTest {

    private static OrderLevel level(long sortKey) {
        OrderLevel level = new OrderLevel();
        level.init(Math.abs(sortKey), sortKey);
        return level;
    }

    @Test
    void insertFindFirstRemoveBasics() {
        LevelTree tree = new LevelTree();
        assertTrue(tree.isEmpty());
        assertNull(tree.first());

        OrderLevel a = level(50);
        OrderLevel b = level(10);
        OrderLevel c = level(90);
        tree.insert(a);
        tree.insert(b);
        tree.insert(c);

        assertEquals(3, tree.size());
        assertSame(b, tree.first());
        assertSame(a, tree.find(50));
        assertNull(tree.find(51));

        tree.remove(b);
        assertSame(a, tree.first());
        tree.remove(a);
        assertSame(c, tree.first());
        tree.remove(c);
        assertTrue(tree.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 99, 20260805})
    void randomChurnMatchesTreeMapReference(long seed) {
        Random random = new Random(seed);
        LevelTree tree = new LevelTree();
        TreeMap<Long, OrderLevel> reference = new TreeMap<>();

        for (int op = 0; op < 30_000; op++) {
            long key = random.nextInt(500) - 250;
            boolean insert = reference.size() < 5 || random.nextBoolean();
            if (insert) {
                if (!reference.containsKey(key)) {
                    OrderLevel node = level(key);
                    tree.insert(node);
                    reference.put(key, node);
                }
            } else {
                Map.Entry<Long, OrderLevel> victim = reference.ceilingEntry(key);
                if (victim == null) {
                    victim = reference.lastEntry();
                }
                reference.remove(victim.getKey());
                tree.remove(victim.getValue());
            }

            if (op % 100 == 0) {
                verifyAgainstReference(tree, reference);
            }
        }
        verifyAgainstReference(tree, reference);
    }

    private static void verifyAgainstReference(LevelTree tree, TreeMap<Long, OrderLevel> reference) {
        assertEquals(reference.size(), tree.size());
        assertSame(reference.isEmpty() ? null : reference.firstEntry().getValue(), tree.first());

        List<OrderLevel> inOrder = new ArrayList<>();
        Map<OrderLevel, Integer> heights = new HashMap<>();
        tree.forEachInOrder(node -> {
            inOrder.add(node);
            heights.put(node, 0);
        });

        assertEquals(new ArrayList<>(reference.values()), inOrder);

        // Structural checks: parent links consistent, AVL balance within one.
        for (OrderLevel node : inOrder) {
            if (node.left != null) {
                assertSame(node, node.left.parent, "left child parent link");
            }
            if (node.right != null) {
                assertSame(node, node.right.parent, "right child parent link");
            }
            int expectedHeight = 1 + Math.max(heightOf(node.left), heightOf(node.right));
            assertEquals(expectedHeight, node.height, "stored height");
            int balance = heightOf(node.left) - heightOf(node.right);
            assertTrue(balance >= -1 && balance <= 1, "AVL balance violated: " + balance);
        }
    }

    private static int heightOf(OrderLevel node) {
        return node == null ? 0 : node.height;
    }
}
