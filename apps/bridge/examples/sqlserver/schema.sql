-- Base de démonstration SQL Server (voir examples/postgres/schema.sql pour les explications).
IF OBJECT_ID('mouvements', 'U') IS NOT NULL DROP TABLE mouvements;
IF OBJECT_ID('articles', 'U') IS NOT NULL DROP TABLE articles;
IF OBJECT_ID('kura_operations', 'U') IS NOT NULL DROP TABLE kura_operations;

CREATE TABLE articles (
  code         NVARCHAR(64) PRIMARY KEY,
  designation  NVARCHAR(200) NOT NULL,
  emplacement  NVARCHAR(64),
  service      NVARCHAR(64),
  unite        NVARCHAR(16),
  stock        INT NOT NULL DEFAULT 0,
  modifie_le   DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE mouvements (
  id             INT IDENTITY PRIMARY KEY,
  code           NVARCHAR(64) NOT NULL REFERENCES articles (code),
  sens           NVARCHAR(8) NOT NULL,
  quantite       INT NOT NULL,
  utilisateur    NVARCHAR(128),
  appareil       NVARCHAR(128),
  date_mouvement DATETIME2 NOT NULL,
  op_id          NVARCHAR(36) NOT NULL
);

INSERT INTO articles (code, designation, emplacement, service, unite, stock, modifie_le) VALUES
  (N'REF-0042', N'Roulement 6204-2RS', N'A-03-2', N'Maintenance', N'pcs', 12, '2026-10-01T08:00:00'),
  (N'REF-0100', N'Gants nitrile (boîte de 100)', N'EPI-1', N'EPI', N'boîtes', 31, '2026-10-02T08:00:00'),
  (N'3760123456789', N'Cartouche filtrante', N'B-01-4', N'Maintenance', N'pcs', 5, '2026-10-03T08:00:00');
