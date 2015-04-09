package org.ahocorasick.trie;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import junit.framework.Assert;

/**
 * Test the lookup of root keywords in the Trie
 */
public class TrieRootKeywordsTest {

    private static Trie trie = new Trie().removeOverlaps().onlyWholeWords().caseInsensitive();

    @Test
    public void testTrie(){

        trie.addKeyword("foo", "root", "brand");
        trie.addKeyword("bar", "root", "brand");
        trie.addKeyword("bar baz", "root", "brand");
        trie.addKeyword("foo2", "root1", "brand1");
        trie.addKeyword("foo2", "root2", "brand2");

        Set<String> tags = new HashSet<>(Arrays.asList("brand"));

        // simple lookup
        Assert.assertEquals(1, trie.lookup("foo").size());
        Assert.assertEquals(tags, trie.lookup("foo").get("root"));

        // simple contains lookup
        Assert.assertEquals(1, trie.lookup("foo bar").size());
        Assert.assertEquals(tags, trie.lookup("foo bar").get("root"));

        // case-insensitive lookup
        Assert.assertEquals(1, trie.lookup("FoO bAr").size());
        Assert.assertEquals(tags, trie.lookup("FoO bAr").get("root"));

        // remove overlaps
        Assert.assertEquals(1, trie.lookup("bar baz").size());
        Assert.assertEquals(tags, trie.lookup("bar baz").get("root"));

        // only whole words
        Assert.assertEquals(0, trie.lookup("bars baz").size());

        // no contains lookups
        Assert.assertEquals(0, trie.lookup("food").size());
    }
}
