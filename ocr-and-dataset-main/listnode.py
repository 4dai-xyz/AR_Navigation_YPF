class ListNode:
    def __init__(self, key=None, value=None):
        self.key = key
        self.value = value
        self.prev = None
        self.next = None


class LRUCache:
    def __init__(self,capacity:int):
        self.capacity = capacity
        self.cache = {}

        self.head = ListNode()
        self.tail = ListNode()
        self.head.next = self.tail
        self.tail.prev = self.head

    def move_to_head(self,node):
        node.prev.next = node.next
        node.next.prev = node.prev

        node.prev = self.head
        node.next = self.head.next  
        self.head.next.prev = node
        self.head.next = node
    def remove_tail(self):
        old_node = self.tail.prev
        old_node.prev.next = self.tail
        self.tail.prev = old_node.prev
        return old_node
    def get(self,key:int) -> int:
        if key not in self.cache:
            return -1
        node = self.cache[key]
        self.move_to_head(node)
        return node.value
    def put(self,key:int, value:int) -> None:
        if key in self.cache:
            node = self.cache[key]
            node.value = value
            self.move_to_head(node)
        else:
            new_node = ListNode(key,value)
            self.cache[key] = new_node

            new_node.prev = self.head
            new_node.next = self.head.next
            self.head.next.prev = new_node
            self.head.next = new_node

            if len(self.cache) > self.capacity:
                removed = self.remove_tail()
                self.cache.pop(removed.key)

