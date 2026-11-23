package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** {@code search_result} (Story 3.9): written once. Hibernate fills {@code owner_id} and filters every query by it. */
@Entity(name = "SearchResult")
@Table(name = "search_result")
class SearchResultEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "search_id", nullable = false, updatable = false)
    private UUID searchId;

    @Column(name = "rank", nullable = false, updatable = false)
    private int resultRank;

    @Column(name = "passage_id", updatable = false)
    private UUID passageId;

    @Column(name = "record_id", updatable = false)
    private UUID recordId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "tier", nullable = false, updatable = false, length = 16)
    private String tier;

    protected SearchResultEntity() {
        // for Hibernate
    }

    SearchResultEntity(SearchResultRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.searchId = row.searchId();
        this.resultRank = row.rank();
        this.passageId = row.passageId();
        this.recordId = row.recordId();
        this.versionId = row.versionId();
        this.tier = row.tier();
    }

    SearchResultRow row() {
        return new SearchResultRow(id, ownerId, searchId, resultRank, passageId, recordId, versionId, tier);
    }
}
