package io.github.rajeevchaurasia.orderbook.book;

/**
 * Intrusive AVL tree of price levels, keyed by {@link OrderLevel#sortKey}.
 * The levels themselves are the tree nodes (left/right/parent/height live on
 * OrderLevel), so inserting or removing a level allocates nothing; combined
 * with {@link LevelPool} recycling, price-level churn is free of garbage.
 * This is why the book does not use a JDK or fastutil sorted map: their tree
 * entries are allocated per insertion, which showed up as roughly 18 B per
 * command under level churn in the GC-profiled benchmarks.
 *
 * <p>Bids store {@code sortKey = -price} and asks {@code sortKey = price},
 * so the minimum key is always the best level and one code path serves both
 * sides. Prices are validated positive, so negation cannot overflow.
 *
 * <p>Single-threaded by design: only the engine thread touches the tree.
 */
public final class LevelTree {

    private OrderLevel root;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return root == null;
    }

    /** The level with the smallest sort key: the best level of the side. */
    public OrderLevel first() {
        OrderLevel node = root;
        if (node == null) {
            return null;
        }
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    public OrderLevel find(long sortKey) {
        OrderLevel node = root;
        while (node != null) {
            if (sortKey < node.sortKey) {
                node = node.left;
            } else if (sortKey > node.sortKey) {
                node = node.right;
            } else {
                return node;
            }
        }
        return null;
    }

    /** Inserts a level whose sortKey is not already present. */
    public void insert(OrderLevel level) {
        level.left = null;
        level.right = null;
        level.height = 1;
        if (root == null) {
            level.parent = null;
            root = level;
            size++;
            return;
        }
        OrderLevel node = root;
        for (;;) {
            if (level.sortKey < node.sortKey) {
                if (node.left == null) {
                    node.left = level;
                    break;
                }
                node = node.left;
            } else if (level.sortKey > node.sortKey) {
                if (node.right == null) {
                    node.right = level;
                    break;
                }
                node = node.right;
            } else {
                throw new IllegalStateException("Duplicate level key: " + level.sortKey);
            }
        }
        level.parent = node;
        size++;
        rebalance(node);
    }

    /** Removes a level that is in this tree. */
    public void remove(OrderLevel level) {
        OrderLevel rebalanceFrom;
        if (level.left != null && level.right != null) {
            // Two children: splice the in-order successor into this position.
            OrderLevel successor = level.right;
            while (successor.left != null) {
                successor = successor.left;
            }
            OrderLevel successorParent = successor.parent;

            // Detach the successor (it has no left child by construction).
            if (successorParent == level) {
                rebalanceFrom = successor;
            } else {
                rebalanceFrom = successorParent;
                successorParent.left = successor.right;
                if (successor.right != null) {
                    successor.right.parent = successorParent;
                }
                successor.right = level.right;
                level.right.parent = successor;
            }
            successor.left = level.left;
            if (level.left != null) {
                level.left.parent = successor;
            }
            successor.height = level.height;
            replaceInParent(level, successor);
        } else {
            OrderLevel child = level.left != null ? level.left : level.right;
            rebalanceFrom = level.parent;
            replaceInParent(level, child);
        }
        level.left = null;
        level.right = null;
        level.parent = null;
        level.height = 0;
        size--;
        if (rebalanceFrom != null) {
            rebalance(rebalanceFrom);
        }
    }

    /** Visits levels in ascending sort-key order (best level first). */
    public void forEachInOrder(java.util.function.Consumer<OrderLevel> action) {
        visit(root, action);
    }

    private static void visit(OrderLevel node, java.util.function.Consumer<OrderLevel> action) {
        if (node == null) {
            return;
        }
        visit(node.left, action);
        action.accept(node);
        visit(node.right, action);
    }

    private void replaceInParent(OrderLevel node, OrderLevel replacement) {
        OrderLevel parent = node.parent;
        if (parent == null) {
            root = replacement;
        } else if (parent.left == node) {
            parent.left = replacement;
        } else {
            parent.right = replacement;
        }
        if (replacement != null) {
            replacement.parent = parent;
        }
    }

    private static int height(OrderLevel node) {
        return node == null ? 0 : node.height;
    }

    private static void updateHeight(OrderLevel node) {
        node.height = 1 + Math.max(height(node.left), height(node.right));
    }

    private static int balance(OrderLevel node) {
        return height(node.left) - height(node.right);
    }

    /** Walks from node to the root, updating heights and rotating as needed. */
    private void rebalance(OrderLevel node) {
        while (node != null) {
            updateHeight(node);
            int balance = balance(node);
            if (balance > 1) {
                if (balance(node.left) < 0) {
                    rotateLeft(node.left);
                }
                node = rotateRight(node);
            } else if (balance < -1) {
                if (balance(node.right) > 0) {
                    rotateRight(node.right);
                }
                node = rotateLeft(node);
            }
            node = node.parent;
        }
    }

    private OrderLevel rotateLeft(OrderLevel node) {
        OrderLevel pivot = node.right;
        node.right = pivot.left;
        if (pivot.left != null) {
            pivot.left.parent = node;
        }
        replaceInParent(node, pivot);
        pivot.left = node;
        node.parent = pivot;
        updateHeight(node);
        updateHeight(pivot);
        return pivot;
    }

    private OrderLevel rotateRight(OrderLevel node) {
        OrderLevel pivot = node.left;
        node.left = pivot.right;
        if (pivot.right != null) {
            pivot.right.parent = node;
        }
        replaceInParent(node, pivot);
        pivot.right = node;
        node.parent = pivot;
        updateHeight(node);
        updateHeight(pivot);
        return pivot;
    }
}
