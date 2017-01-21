package bot;

class CustomQueue {
    int[] elements = new int[1024];
    public int head;
    public int tail;

    public CustomQueue () {
    }

    public void addLast(int e) {
        elements[tail] = e;
        if ( (tail = (tail + 1) & (elements.length - 1)) == head)
            doubleCapacity();
    }

    public int size() {
        return (tail - head) & (elements.length - 1);
    }

    public boolean isEmpty() {
        return head == tail;
    }

    public int pollFirst() {
        int h = head;
        head = (h + 1) & (elements.length - 1);
        return elements[h];
    }

    private void doubleCapacity() {
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
    }
}
