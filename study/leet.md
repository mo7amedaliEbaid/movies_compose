# LeetCode Solutions

---

## 217. Contains Duplicate — Easy

**Problem:** Return `true` if any value appears at least twice in the array.

**Approach:** HashSet — add each element; if `add()` returns `false`, a duplicate exists.
- Time: O(n) | Space: O(n)

```kotlin
class Solution {
    fun containsDuplicate(nums: IntArray): Boolean {
        val seen = HashSet<Int>()
        for (n in nums) {
            if (!seen.add(n)) return true
        }
        return false
    }
}
```

---

## 242. Valid Anagram — Easy

**Problem:** Return `true` if `t` is an anagram of `s`.

**Approach:** Frequency count array of size 26 — increment for `s`, decrement for `t`. All zeros = anagram.
- Time: O(n) | Space: O(1)

```kotlin
class Solution {
    fun isAnagram(s: String, t: String): Boolean {
        if (s.length != t.length) return false
        val count = IntArray(26)
        for (i in s.indices) {
            count[s[i] - 'a']++
            count[t[i] - 'a']--
        }
        return count.all { it == 0 }
    }
}
```

> **Follow-up (Unicode):** Replace `IntArray(26)` with a `HashMap<Char, Int>` and use `getOrDefault` to handle any character set.
