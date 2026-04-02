CREATE TABLE IF NOT EXISTS document_templates (
  id          SERIAL PRIMARY KEY,
  name        VARCHAR(200) NOT NULL,
  description TEXT,
  content     TEXT,
  version     VARCHAR(20),
  created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS signed_documents (
  id             SERIAL PRIMARY KEY,
  template_id    INT REFERENCES document_templates(id),
  customer_id    VARCHAR(100),
  customer_name  VARCHAR(200),
  signature_data TEXT,
  signed_at      TIMESTAMP DEFAULT NOW()
);

INSERT INTO document_templates (name, description, content, version) VALUES
  ('Bireysel Kredi Sozlesmesi',
   'Bireysel musteriler icin standart kredi sozlesmesi sablonu',
   'Isbu sozlesme, Akbank T.A.S. ile {{MUSTERI_ADI}} (TC: {{TC_NO}}) arasinda bireysel kredi kullandirilmasina iliskin hukum ve kosullari duzenlemektedir. Kredi Tutari: {{KREDI_TUTARI}} TL, Vade: {{VADE}} Ay, Faiz Orani: %{{FAIZ}} aylik.',
   'v3.2'),
  ('Konut Kredisi Sozlesmesi',
   'Mortgage ve konut finansmani sozlesme sablonu',
   'Isbu sozlesme, Akbank T.A.S. ile {{MUSTERI_ADI}} arasinda konut kredisi kullandirilmasina iliskin hukum ve kosullari duzenlemektedir. Konut Adresi: {{ADRES}}, Kredi Tutari: {{KREDI_TUTARI}} TL, Vade: {{VADE}} Ay.',
   'v2.1'),
  ('Kredi Karti Basvuru Formu',
   'Yeni kredi karti basvurusu ve sozlesme sablonu',
   'Akbank Kredi Karti Basvuru ve Sozlesme Formu. Basvuru Sahibi: {{MUSTERI_ADI}}, Kart Limiti: {{LIMIT}} TL, Kart Turu: {{KART_TURU}}.',
   'v4.0'),
  ('Ticari Kredi Sozlesmesi',
   'KOBI ve kurumsal musteriler icin ticari kredi sablonu',
   'Isbu sozlesme, Akbank T.A.S. ile {{FIRMA_ADI}} arasinda ticari kredi kullandirilmasina iliskin duzenlenmistir. Vergi No: {{VERGI_NO}}, Kredi Tutari: {{KREDI_TUTARI}} TL.',
   'v1.8');

INSERT INTO signed_documents (template_id, customer_id, customer_name, signature_data) VALUES
  (1, 'MUS-001', 'Ahmet Yilmaz', 'c2lnbmF0dXJlX2RhdGFfYmFzZTY0XzE='),
  (1, 'MUS-002', 'Fatma Kaya',   'c2lnbmF0dXJlX2RhdGFfYmFzZTY0XzI='),
  (2, 'MUS-003', 'Mehmet Demir', 'c2lnbmF0dXJlX2RhdGFfYmFzZTY0XzM='),
  (3, 'MUS-004', 'Ayse Celik',   'c2lnbmF0dXJlX2RhdGFfYmFzZTY0XzQ='),
  (4, 'MUS-005', 'Ali Sahin',    'c2lnbmF0dXJlX2RhdGFfYmFzZTY0XzU=');
