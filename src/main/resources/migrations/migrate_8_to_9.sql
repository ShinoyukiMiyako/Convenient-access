-- 迁移脚本: 版本 8 到版本 9
-- 新增整合包版本与文件条目，结构必须与 schema/pack.sql 保持一致。
CREATE TABLE IF NOT EXISTS pack_version (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    version      TEXT    NOT NULL UNIQUE CHECK (length(trim(version)) > 0),
    status       TEXT    NOT NULL CHECK (status IN ('draft', 'published', 'archived')),
    minecraft    TEXT    NOT NULL CHECK (length(trim(minecraft)) > 0),
    loader_kind  TEXT    NOT NULL CHECK (length(trim(loader_kind)) > 0),
    loader_ver   TEXT    NOT NULL CHECK (length(trim(loader_ver)) > 0),
    note         TEXT,
    created_at   INTEGER NOT NULL CHECK (created_at >= 0),
    published_at INTEGER CHECK (published_at IS NULL OR published_at >= 0),
    CONSTRAINT chk_pack_version_publication_time CHECK (
        (status = 'draft' AND published_at IS NULL)
        OR (status IN ('published', 'archived') AND published_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS pack_entry (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    version_id   INTEGER NOT NULL REFERENCES pack_version(id) ON DELETE CASCADE,
    path         TEXT    NOT NULL CHECK (length(path) > 0),
    kind         TEXT    NOT NULL CHECK (kind IN ('platform', 'custom')),
    policy       TEXT    NOT NULL CHECK (policy IN ('managed', 'seeded', 'optional')),
    sha1         TEXT    NOT NULL CHECK (
        length(sha1) = 40 AND sha1 NOT GLOB '*[^0-9a-f]*'
    ),
    size         INTEGER NOT NULL CHECK (size >= 0),
    download_url TEXT    NOT NULL CHECK (length(trim(download_url)) > 0),
    platform     TEXT,
    project_id   TEXT,
    project_name TEXT,
    version_ext  TEXT,
    CONSTRAINT uq_pack_entry_version_path UNIQUE (version_id, path COLLATE NOCASE),
    CONSTRAINT chk_pack_entry_platform_fields CHECK (
        (kind = 'custom'
            AND platform IS NULL
            AND project_id IS NULL
            AND project_name IS NULL
            AND version_ext IS NULL)
        OR
        (kind = 'platform'
            AND platform IS NOT NULL
            AND platform IN ('modrinth', 'curseforge')
            AND project_id IS NOT NULL AND length(trim(project_id)) > 0
            AND project_name IS NOT NULL AND length(trim(project_name)) > 0
            AND version_ext IS NOT NULL AND length(trim(version_ext)) > 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_pack_entry_version ON pack_entry(version_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pack_version_single_published
    ON pack_version(status) WHERE status = 'published';

CREATE TRIGGER IF NOT EXISTS trg_pack_version_released_replace
BEFORE INSERT ON pack_version
WHEN EXISTS (
    SELECT 1 FROM pack_version
    WHERE published_at IS NOT NULL
      AND (id = NEW.id OR version = NEW.version)
)
BEGIN
    SELECT RAISE(ABORT, '已发布的整合包版本不可替换');
END;

CREATE TRIGGER IF NOT EXISTS trg_pack_version_status_transition
BEFORE UPDATE OF status ON pack_version
WHEN NOT (
    NEW.status = OLD.status
    OR (OLD.status = 'draft' AND NEW.status = 'published')
    OR (OLD.status = 'published' AND NEW.status = 'archived')
    OR (OLD.status = 'archived' AND NEW.status = 'published')
)
BEGIN
    SELECT RAISE(ABORT, '非法的整合包版本状态迁移');
END;

CREATE TRIGGER IF NOT EXISTS trg_pack_version_released_content_update
BEFORE UPDATE OF id, version, minecraft, loader_kind, loader_ver, note, created_at, published_at
ON pack_version
WHEN OLD.published_at IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, '已发布的整合包版本不可修改');
END;

CREATE TRIGGER IF NOT EXISTS trg_pack_version_released_delete
BEFORE DELETE ON pack_version
WHEN OLD.published_at IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, '已发布的整合包版本不可删除');
END;

CREATE TRIGGER IF NOT EXISTS trg_pack_entry_released_insert
BEFORE INSERT ON pack_entry
WHEN EXISTS (
    SELECT 1 FROM pack_version
    WHERE id = NEW.version_id AND published_at IS NOT NULL
)
BEGIN
    SELECT RAISE(ABORT, '已发布的整合包条目不可新增');
END;

CREATE TRIGGER IF NOT EXISTS trg_pack_entry_released_update
BEFORE UPDATE ON pack_entry
WHEN EXISTS (
    SELECT 1 FROM pack_version
    WHERE id IN (OLD.version_id, NEW.version_id) AND published_at IS NOT NULL
)
BEGIN
    SELECT RAISE(ABORT, '已发布的整合包条目不可修改');
END;

CREATE TRIGGER IF NOT EXISTS trg_pack_entry_released_delete
BEFORE DELETE ON pack_entry
WHEN EXISTS (
    SELECT 1 FROM pack_version
    WHERE id = OLD.version_id AND published_at IS NOT NULL
)
BEGIN
    SELECT RAISE(ABORT, '已发布的整合包条目不可删除');
END;
