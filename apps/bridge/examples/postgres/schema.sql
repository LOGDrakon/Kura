-- Base de démonstration : une table d'articles comme on en trouve dans une GMAO ou un ERP.
-- Dans la vraie vie, ces tables existent déjà : seules les requêtes de kura-bridge.yaml s'y adaptent.
DROP TABLE IF EXISTS mouvements;
DROP TABLE IF EXISTS articles;
DROP TABLE IF EXISTS kura_operations;

CREATE TABLE articles (
  code         VARCHAR(64) PRIMARY KEY,
  designation  VARCHAR(200) NOT NULL,
  emplacement  VARCHAR(64),
  service      VARCHAR(64),
  unite        VARCHAR(16),
  stock        INTEGER NOT NULL DEFAULT 0,
  modifie_le   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Historique des mouvements de la GMAO, alimenté par la requête after_apply.
CREATE TABLE mouvements (
  id             SERIAL PRIMARY KEY,
  code           VARCHAR(64) NOT NULL REFERENCES articles (code),
  sens           VARCHAR(8) NOT NULL,      -- delta (entrée/sortie) ou set (inventaire)
  quantite       INTEGER NOT NULL,
  utilisateur    VARCHAR(128),
  appareil       VARCHAR(128),
  date_mouvement TIMESTAMPTZ NOT NULL,
  op_id          VARCHAR(36) NOT NULL
);

INSERT INTO articles (code, designation, emplacement, service, unite, stock, modifie_le) VALUES
  ('REF-0042', 'Roulement 6204-2RS', 'A-03-2', 'Maintenance', 'pcs', 12, '2026-10-01T08:00:00Z'),
  ('REF-0100', 'Gants nitrile (boîte de 100)', 'EPI-1', 'EPI', 'boîtes', 31, '2026-10-02T08:00:00Z'),
  ('3760123456789', 'Cartouche filtrante', 'B-01-4', 'Maintenance', 'pcs', 5, '2026-10-03T08:00:00Z');
