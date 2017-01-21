package bot;

class CustomQueue {
    int[] elements = new int[1024];
    int mask = 1023;
    public int head;
    public int tail;

    public CustomQueue () {
    }

    public void addLast(int e) {
        elements[tail & mask] = e;
        if (tail++ - head == mask)
            doubleCapacity();
    }

    public int size() {
        return tail - head;
    }

    public boolean isEmpty() {
        return head == tail;
    }

    public int pollFirst() {
        return elements[head++ & mask];
    }

    private void doubleCapacity() {
        head &= mask;
        tail &= mask;
        assert head == tail;
        int p = head;
        int n = elements.length;
        int r = n - p; // number of elements to the right of p
        int newCapacity = n << 1;
        if (newCapacity < 0)
            throw new IllegalStateException("Sorry, deque too big");
        int[] a = new int[newCapacity];
        System.arraycopy(elements, p, a, 0, r);
        System.arraycopy(elements, 0, a, r, p);
        elements = a;
        head = 0;
        tail = n;
        mask = elements.length - 1;
    }
}
