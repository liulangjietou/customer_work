package com.richard.fyoung.customerwork.capability.deadletter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内死信存储（默认实现，离线可测）。
 *
 * <p><b>生产不可用</b>：死信的全部意义是"进程挂了之后这笔还能补回来"，
 * 而进程内存储恰恰在最需要它的那次故障中一起没了。生产切
 * {@code dead-letter.store-mode=jdbc}。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryDeadLetterStore implements DeadLetterStore {

    /** 到期优先：越早该重投的越先拿。 */
    private static final Comparator<DeadLetter> EARLIEST_DUE_FIRST =
        Comparator.comparingLong(DeadLetter::getNextRetryAtMs);

    private static final Comparator<DeadLetter> NEWEST_FIRST =
        Comparator.comparingLong(DeadLetter::getCreatedAtMs).reversed();

    private final Map<String, DeadLetter> letters = new ConcurrentHashMap<>();

    @Override
    public void save(DeadLetter letter) {
        if (letter == null || letter.getId() == null) {
            return;
        }
        letters.put(letter.getId(), letter);
    }

    @Override
    public Optional<DeadLetter> find(String id) {
        return Optional.ofNullable(letters.get(id));
    }

    @Override
    public List<DeadLetter> findDue(long nowMs, int limit) {
        List<DeadLetter> due = new ArrayList<>();
        for (DeadLetter letter : letters.values()) {
            if (letter.dueAt(nowMs)) {
                due.add(letter);
            }
        }
        due.sort(EARLIEST_DUE_FIRST);
        return List.copyOf(due.subList(0, Math.min(Math.max(limit, 0), due.size())));
    }

    @Override
    public List<DeadLetter> findByStatus(DeadLetterStatus status, int limit) {
        List<DeadLetter> matched = new ArrayList<>();
        for (DeadLetter letter : letters.values()) {
            if (letter.getStatus() == status) {
                matched.add(letter);
            }
        }
        matched.sort(NEWEST_FIRST);
        return List.copyOf(matched.subList(0, Math.min(Math.max(limit, 0), matched.size())));
    }

    @Override
    public long count(DeadLetterStatus status) {
        return letters.values().stream().filter(letter -> letter.getStatus() == status).count();
    }
}
