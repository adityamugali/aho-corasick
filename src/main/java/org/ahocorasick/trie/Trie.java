package org.ahocorasick.trie;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

import org.ahocorasick.interval.IntervalTree;
import org.ahocorasick.interval.Intervalable;

/**
 * Based on the Aho-Corasick white paper, Bell technologies: ftp://163.13.200.222/assistant/bearhero/prog/%A8%E4%A5%A6/ac_bm.pdf
 *
 * @author Robert Bor
 */
public class Trie {

    private TrieConfig trieConfig;

    private State rootState;

    private boolean failureStatesConstructed = false;

    public Trie(TrieConfig trieConfig) {
        this.trieConfig = trieConfig;
        this.rootState = new State();
    }

    public Trie() {
        this(new TrieConfig());
    }

    public Trie caseInsensitive() {
        this.trieConfig.setCaseInsensitive(true);
        return this;
    }

    public Trie removeOverlaps() {
        this.trieConfig.setAllowOverlaps(false);
        return this;
    }

    public Trie onlyWholeWords() {
        this.trieConfig.setOnlyWholeWords(true);
        return this;
    }

    public void addKeyword(String keyword) {
        if (keyword == null || keyword.length() == 0) {
            return;
        }
        State currentState = this.rootState;
        for (Character character : keyword.toCharArray()) {
            currentState = currentState.addState(character);
        }
        currentState.addEmit(keyword);
    }

    public void addKeyword(String keyword, String rootKeyword, String tag) {
        if (keyword == null || keyword.length() == 0) {
            return;
        }
        State currentState = this.rootState;
        for (Character character : keyword.toCharArray()) {
            currentState = currentState.addState(Character.toLowerCase(character));
        }
        currentState.addEmit(keyword);

        // this is the leaf , add the root keyword
        currentState.addRootKeyword(rootKeyword, tag);
    }

    public Collection<Token> tokenize(String text) {

        Collection<Token> tokens = new ArrayList<Token>();

        Collection<Emit> collectedEmits = parseText(text);
        int lastCollectedPosition = -1;
        for (Emit emit : collectedEmits) {
            if (emit.getStart() - lastCollectedPosition > 1) {
                tokens.add(createFragment(emit, text, lastCollectedPosition));
            }
            tokens.add(createMatch(emit, text));
            lastCollectedPosition = emit.getEnd();
        }
        if (text.length() - lastCollectedPosition > 1) {
            tokens.add(createFragment(null, text, lastCollectedPosition));
        }

        return tokens;
    }

    private Token createFragment(Emit emit, String text, int lastCollectedPosition) {
        return new FragmentToken(text.substring(lastCollectedPosition + 1, emit == null ? text.length() : emit.getStart()));
    }

    private Token createMatch(Emit emit, String text) {
        return new MatchToken(text.substring(emit.getStart(), emit.getEnd() + 1), emit);
    }

    @SuppressWarnings("unchecked")
    public Collection<Emit> parseText(String text) {
        checkForConstructedFailureStates();

        int position = 0;
        State currentState = this.rootState;
        List<Emit> collectedEmits = new ArrayList<Emit>();
        for (Character character : text.toCharArray()) {
            if (trieConfig.isCaseInsensitive()) {
                character = Character.toLowerCase(character);
            }
            currentState = getState(currentState, character);
            storeEmits(position, currentState, collectedEmits);
            position++;
        }

        if (trieConfig.isOnlyWholeWords()) {
            removePartialMatches(text, collectedEmits);
        }

        if (!trieConfig.isAllowOverlaps()) {
            IntervalTree intervalTree = new IntervalTree((List<Intervalable>) (List<?>) collectedEmits);
            intervalTree.removeOverlaps((List<Intervalable>) (List<?>) collectedEmits);
        }

        return collectedEmits;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Set<String>> lookup(String text) {
        checkForConstructedFailureStates();

        int position = 0;
        State currentState = this.rootState;
        List<Emit> collectedEmits = new ArrayList<Emit>();
        Map<Emit, Map<String, Set<String>>> emitRootKeywords = new HashMap<>();
        StringBuffer transitions = new StringBuffer();

        for (Character character : text.toCharArray()) {
            if (trieConfig.isCaseInsensitive()) {
                character = Character.toLowerCase(character);
            }
            currentState = getState(currentState, character);
            storeEmits(position, currentState, collectedEmits, emitRootKeywords);
            position++;

            // keep track on transitions
            transitions.append(character);
        }

        if (trieConfig.isOnlyWholeWords()) {
            removePartialMatches(text, collectedEmits);
        }

        if (!trieConfig.isAllowOverlaps()) {
            IntervalTree intervalTree = new IntervalTree((List<Intervalable>) (List<?>) collectedEmits);
            intervalTree.removeOverlaps((List<Intervalable>) (List<?>) collectedEmits);
        }

        Map<String, Set<String>> rootKeywords = new HashMap<>();

        // the collected emits are now filtered
        for (Emit emit : collectedEmits) {
            Map<String, Set<String>> map = emitRootKeywords.get(emit);

            for (String root : map.keySet()) {
                Set<String> tags = rootKeywords.get(root);
                if (tags == null) {
                    rootKeywords.put(root, (tags = new HashSet<>()));
                }
                tags.addAll(map.get(root));
            }
        }

        return rootKeywords;
    }

    private void removePartialMatches(String searchText, List<Emit> collectedEmits) {
        long size = searchText.length();
        List<Emit> removeEmits = new ArrayList<Emit>();
        for (Emit emit : collectedEmits) {
            if ((emit.getStart() == 0 ||
                 !Character.isAlphabetic(searchText.charAt(emit.getStart() - 1))) &&
                (emit.getEnd() + 1 == size ||
                 !Character.isAlphabetic(searchText.charAt(emit.getEnd() + 1)))) {
                continue;
            }
            removeEmits.add(emit);
        }

        for (Emit removeEmit : removeEmits) {
            collectedEmits.remove(removeEmit);
        }
    }

    private State getState(State currentState, Character character) {
        State newCurrentState = currentState.nextState(character);
        while (newCurrentState == null) {
            currentState = currentState.failure();
            newCurrentState = currentState.nextState(character);
        }
        return newCurrentState;
    }

    private void checkForConstructedFailureStates() {
        if (!this.failureStatesConstructed) {
            constructFailureStates();
        }
    }

    private void constructFailureStates() {
        Queue<State> queue = new LinkedBlockingDeque<State>();

        // First, set the fail state of all depth 1 states to the root state
        for (State depthOneState : this.rootState.getStates()) {
            depthOneState.setFailure(this.rootState);
            queue.add(depthOneState);
        }
        this.failureStatesConstructed = true;

        // Second, determine the fail state for all depth > 1 state
        while (!queue.isEmpty()) {
            State currentState = queue.remove();

            for (Character transition : currentState.getTransitions()) {
                State targetState = currentState.nextState(transition);
                queue.add(targetState);

                State traceFailureState = currentState.failure();
                while (traceFailureState.nextState(transition) == null) {
                    traceFailureState = traceFailureState.failure();
                }
                State newFailureState = traceFailureState.nextState(transition);
                targetState.setFailure(newFailureState);
                targetState.addEmit(newFailureState.emit());
            }
        }
    }

    private void storeEmits(int position, State currentState, List<Emit> collectedEmits) {
        Collection<String> emits = currentState.emit();
        if (emits != null && !emits.isEmpty()) {
            for (String emit : emits) {
                collectedEmits.add(new Emit(position - emit.length() + 1, position, emit));
            }
        }
    }

    private void storeEmits(int position, State currentState, List<Emit> collectedEmits, Map<Emit, Map<String, Set<String>>> emitRootKeywords) {
        Collection<String> emits = currentState.emit();
        if (emits != null && !emits.isEmpty()) {
            for (String emit : emits) {
                Emit emitObj = new Emit(position - emit.length() + 1, position, emit);
                collectedEmits.add(emitObj);

                Map<String, Set<String>> rootKeywords = emitRootKeywords.get(emitObj);
                if (rootKeywords == null) {
                    emitRootKeywords.put(emitObj, (rootKeywords = new HashMap<>()));
                }

                for (String rootKeyword : currentState.getRootKeywords().keySet()) {
                    // multiple tags associated with a root keyword
                    Set<String> tags = currentState.getRootKeywords().get(rootKeyword);
                    Set<String> existingTags = null;

                    if ((existingTags = rootKeywords.get(rootKeyword)) != null) {
                        existingTags.addAll(tags);
                    } else {
                        existingTags = new HashSet<>(tags);
                        rootKeywords.put(rootKeyword, existingTags);
                    }
                }
            }
        }
    }
}
