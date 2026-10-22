-- Base de démonstration MySQL / MariaDB (voir examples/postgres/schema.sql pour les explications).
DROP TABLE IF EXISTS mouvements;
DROP TABLE IF EXISTS articles;
DROP TABLE IF EXISTS kura_operations;

CREATE TABLE articles (
  code         VARCHAR(64) PRIMARY KEY,
  designation  VARCHAR(200) NOT NULL,
  emplacement  VARCHAR(64),
  service      VARCHAR(64),
  unite        VARCHAR(16),
  stock        INT NOT NULL DEFAULT 0,
  modifie_le   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE mouvements (
  id             INT AUTO_INCREMENT PRIMARY KEY,
  code           VARCHAR(64) NOT NULL,
  sens           VARCHAR(8) NOT NULL,
  quantite       INT NOT NULL,
  utilisateur    VARCHAR(128),
  appareil       VARCHAR(128),
  date_mouvement DATETIME(3) NOT NULL,
  op_id          VARCHAR(36) NOT NULL,
  FOREIGN KEY (code) REFERENCES articles (code)
) DEFAULT CHARSET = utf8mb4;

INSERT INTO articles (code, designation, emplacement, service, unite, stock, modifie_le) VALUES
  ('REF-0042', 'Roulement 6204-2RS', 'A-03-2', 'Maintenance', 'pcs', 12, '2026-10-01 08:00:00'),
  ('REF-0100', 'Gants nitrile (boîte de 100)', 'EPI-1', 'EPI', 'boîtes', 31, '2026-10-02 08:00:00'),
  ('3760123456789', 'Cartouche filtrante', 'B-01-4', 'Maintenance', 'pcs', 5, '2026-10-03 08:00:00');
