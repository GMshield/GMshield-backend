-- Story 1.19 (AD-11, AD-17, AD-19): one page view per page of a material version, and the passages of a page read
-- through an index. The previous backend release writes none of it and keeps working: it never reads page_view, and
-- it never meets a version_file of the role 'picture' unless a worker of pipeline "5" reports one, which needs this
-- release (the worker's floor is internal contract 1.5.0).
-- version_file gains the role 'picture': a WebP picture of one page (first_page = last_page = that page), sent back
-- by the layout or OCR stage and adopted by the report that names it.
-- page_view is a library table: each row carries its owner, filled and filtered by Hibernate @TenantId, and every key
-- and reference includes it, as in V4, V7, V9, V10 and V11. A page of a PDF has its picture (picture_file_id, a file
-- of the version's, with its width and height in pixels); a page of a text material has none (all three null).
-- content holds the page's text lines with their boxes ({"lines": [{"text", "box"}]}), or a text page's text
-- ({"text": "..."}), as JSON, stored only encrypted through the vault, bound to the entity "page_view", the field
-- "content", the row and the owner. A report replaces the page views of the pages it carries; a restarted chain
-- deletes them with the version's passages. Structure only: no trigger, procedure or row-level security.
alter table version_file drop constraint version_file_role_check;
alter table version_file add constraint version_file_role_check
    check (role in ('original', 'batch', 'text', 'picture'));

create table page_view (
    id              uuid        primary key,
    owner_id        uuid        not null,
    version_id      uuid        not null,
    page            integer     not null check (page between 1 and 1000000),
    picture_file_id uuid,
    width           integer     check (width between 1 and 10000),
    height          integer     check (height between 1 and 10000),
    content         bytea       not null,
    created_at      timestamptz not null,
    constraint page_view_owner_id_key unique (owner_id, id),
    constraint page_view_owner_version_page_key unique (owner_id, version_id, page),
    constraint page_view_picture_check check (
        (picture_file_id is null and width is null and height is null)
        or (picture_file_id is not null and width is not null and height is not null)),
    constraint page_view_version_fkey foreign key (owner_id, version_id) references material_version (owner_id, id),
    constraint page_view_picture_fkey foreign key (owner_id, picture_file_id)
        references version_file (owner_id, file_id)
);

create index passage_owner_version_page on passage (owner_id, version_id, page);
