package app.erp.common;

import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Generic REST resource for tenant master data: list (allow-listed filters, paging),
 * get, create, partial update (JSON merge onto the entity, read-only fields ignored).
 * Subclasses declare permissions, filters and validation; workflows get their own endpoints.
 */
public abstract class CrudSupport<E extends TenantEntity> {
    protected final TenantRepo<E> repo;
    protected final ObjectMapper mapper;
    protected final AuditService audit;
    private final Class<E> type;
    private final Perm readPerm;
    private final Perm writePerm;

    protected CrudSupport(Class<E> type, TenantRepo<E> repo, ObjectMapper mapper, AuditService audit, Perm readPerm, Perm writePerm) {
        this.type = type;
        this.repo = repo;
        this.mapper = mapper;
        this.audit = audit;
        this.readPerm = readPerm;
        this.writePerm = writePerm;
    }

    /** Query params usable as equality filters, e.g. status, clientId. */
    protected Set<String> filterable() { return Set.of(); }

    /** Fields searched by ?q= (case-insensitive contains). */
    protected List<String> searchable() { return List.of(); }

    protected Sort defaultSort() { return Sort.by("createdAt").descending(); }

    /** Row-level scope (site / branch / client restrictions) for the current user. */
    protected Specification<E> scope() { return null; }

    protected void validate(E entity, boolean isNew) {}

    protected void afterSave(E entity, boolean isNew) {}

    protected void checkRead() { Authz.require(readPerm); }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResult<E> list(@RequestParam Map<String, String> params) {
        checkRead();
        int page = Integer.parseInt(params.getOrDefault("page", "0"));
        int size = Math.min(500, Integer.parseInt(params.getOrDefault("size", "50")));
        Specification<E> spec = Specification.where(scope());
        for (var e : params.entrySet()) {
            String k = e.getKey();
            if (!filterable().contains(k) || e.getValue() == null || e.getValue().isBlank()) continue;
            spec = spec.and(equalsSpec(k, e.getValue()));
        }
        String q = params.get("q");
        if (q != null && !q.isBlank() && !searchable().isEmpty()) {
            String like = "%" + q.toLowerCase() + "%";
            spec = spec.and((root, cq, cb) -> cb.or(searchable().stream()
                    .map(f -> cb.like(cb.lower(root.get(f).as(String.class)), like)).toArray(Predicate[]::new)));
        }
        Page<E> p = repo.findAll(spec, PageRequest.of(page, size, defaultSort()));
        return new PageResult<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public E get(@PathVariable UUID id) {
        checkRead();
        return load(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public E create(@RequestBody JsonNode body) throws Exception {
        Authz.require(writePerm);
        E e = mapper.treeToValue(body, type);
        validate(e, true);
        E saved = repo.saveAndFlush(e);
        afterSave(saved, true);
        audit.record("CREATE", type.getSimpleName(), saved.getId(), null, saved, Map.of());
        return saved;
    }

    @PatchMapping("/{id}")
    @Transactional
    public E update(@PathVariable UUID id, @RequestBody JsonNode body) throws Exception {
        Authz.require(writePerm);
        E e = load(id);
        String before = mapper.writeValueAsString(e);
        Long expectedVersion = body.hasNonNull("version") ? body.get("version").asLong() : null;
        if (expectedVersion != null && !expectedVersion.equals(e.getVersion())) throw ApiException.conflict("Record was changed by someone else; reload");
        mapper.readerForUpdating(e).readValue(body);
        validate(e, false);
        E saved = repo.saveAndFlush(e);
        afterSave(saved, false);
        audit.record("UPDATE", type.getSimpleName(), saved.getId(), before, saved, Map.of());
        return saved;
    }

    protected E load(UUID id) {
        Specification<E> spec = Specification.where(scope());
        spec = spec.and((root, q, cb) -> cb.equal(root.get("id"), id));
        return repo.findOne(spec).orElseThrow(() -> ApiException.notFound(type.getSimpleName()));
    }

    private Specification<E> equalsSpec(String field, String raw) {
        return (root, q, cb) -> {
            Class<?> jt = root.get(field).getJavaType();
            Object v;
            if (jt == UUID.class) v = UUID.fromString(raw);
            else if (jt == Boolean.class || jt == boolean.class) v = Boolean.parseBoolean(raw);
            else if (jt == LocalDate.class) v = LocalDate.parse(raw);
            else if (jt == Integer.class || jt == int.class) v = Integer.parseInt(raw);
            else v = raw;
            return cb.equal(root.get(field), v);
        };
    }

    public record PageResult<T>(List<T> items, int page, int size, long total) {}
}
