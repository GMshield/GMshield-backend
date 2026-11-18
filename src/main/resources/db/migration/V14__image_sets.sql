-- Story 1.21 (AD-11, AD-16, AD-17, AD-19): photos as the pages of one material. Several images become one ordered set:
-- one material, one version and one tus upload per image, each upload at its place in the order. The previous backend
-- release writes none of it, but it does not understand a failed upload or a version whose format is 'images': rolling
-- back past V14 is safe only while neither exists, which holds until the first release with V14 ships.
-- upload.position is the image's place in its set (1 to 500, numbered without gaps), null for an upload that is not a
-- member of a set (a PDF, a text, an image sent through the plain tus creation). upload.name is the image's file name,
-- stored only encrypted through the vault, bound to the entity "upload", the field "name", the row and the owner; null
-- outside a set. A member that fails its whole-file check, is refused for its format or expires becomes 'failed', with
-- its pieces removed and the reason in upload.failure_code (a code, never a message): the set's version is kept, and
-- the GM replaces the image (a new upload at the same place) or continues without it (later places move up by one).
-- The backend owns the order: it is fixed when the set is created. The version stays 'uploading' until it starts:
-- when no member is open or failed and at least one is complete, it becomes 'waiting' with the format 'images' (an
-- internal code, never an accepted upload format) and its first ingestion step reads every complete member in order.
-- Structure only: no trigger, procedure or row-level security.
alter table upload
    add column position     integer check (position between 1 and 500),
    add column name         bytea,
    add column failure_code varchar(64),
    drop constraint upload_state_check,
    add constraint upload_state_check check (state in ('open', 'complete', 'removing', 'failed')),
    add constraint upload_failure_check check ((state = 'failed') = (failure_code is not null) or state = 'removing');

alter table material_version
    drop constraint material_version_format_check,
    add constraint material_version_format_check
        check (format in ('pdf', 'txt', 'markdown', 'jpeg', 'png', 'webp', 'tiff', 'bmp', 'heic', 'images'));

-- What the sweep looks at for sets: failed members, by their version.
create index upload_state_version on upload (state, owner_id, version_id) where position is not null;
