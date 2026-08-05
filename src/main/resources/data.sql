-- Seed data for heavy-rental-rest-api (Singapore context, SGD amounts, metric units).
--
-- Deliberately NOT seeded here: users, asset_categories, assets, asset_images.
--   * asset_categories / assets / asset_images are owned by AssetDataInitializer
--     (config/AssetDataInitializer.java), which seeds them in Java, guarded by
--     `asset_categories` being empty. Do NOT insert into asset_categories here —
--     since data.sql runs before ApplicationRunners (defer-datasource-initialization=true),
--     any row inserted here would trip that guard and silently prevent
--     AssetDataInitializer from ever seeding assets/images.
--   * The real seeded catalog (see specification/SPEC-asset-mock-data.md) is 4 categories
--     and these 8 assets, referenced below by exact `name`:
--       CAT 320 Excavator            (base_daily_rate 450.00)
--       Komatsu PC210 Excavator      (base_daily_rate 470.00)
--       Genie GS-1930 Scissor Lift   (base_daily_rate 120.00)
--       JLG 2630ES Scissor Lift      (base_daily_rate 140.00)
--       JLG 460SJ Boom Lift          (base_daily_rate 210.00)
--       Genie Z-45 Boom Lift         (base_daily_rate 195.00)
--       Toyota 8FD25 Forklift        (base_daily_rate 150.00)
--       Hyster H2.5FT Forklift       (base_daily_rate 160.00)
--
-- FK linkage assumptions:
--   * users.name values expected to exist: 'admin' (already seeded), 'Alex Tan' (customer),
--     'Ravi Kumar' (admin), 'Ah Tan' (driver). rental_plan.customer_id is NOT NULL, so the
--     'Alex Tan' row MUST exist before this file runs or those inserts will fail outright.
--     bookings.customer_id / ai_recommendations.user_id are nullable, so a missing name there
--     only degrades to NULL rather than failing.
--   * asset_id lookups below use `(SELECT id FROM assets WHERE name = '...')` against the exact
--     names above. These are nullable FK columns, so if AssetDataInitializer hasn't run yet
--     (assets table still empty), rows here still insert with a NULL asset_id — spot-check
--     with `SELECT * FROM booking_items WHERE asset_id IS NULL` after first boot.
--   * Plain one-shot inserts (explicit IDs, no ON CONFLICT/sequence resync) per prior agreement.

-- ============================================================
-- 1. rental_plan
-- ============================================================
INSERT INTO rental_plan (id, customer_id, start_date, end_date, total_amount, status, site_address, site_postal_code, created_at, updated_at) VALUES
  (1, (SELECT id FROM users WHERE name = 'Alex Tan'), '2026-08-20', '2026-08-25', 1050.00, 'DRAFT',    '88 Tuas South Ave 3',              'S(637311)', '2026-08-01 09:00:00', '2026-08-01 09:00:00'),
  (2, (SELECT id FROM users WHERE name = 'Alex Tan'), '2026-08-18', '2026-08-22', 480.00,  'SAVED',    '15 Pioneer Sector 1',              'S(628413)', '2026-08-02 10:00:00', '2026-08-02 10:00:00'),
  (3, (SELECT id FROM users WHERE name = 'Alex Tan'), '2026-09-01', '2026-09-05', 1440.00, 'QUOTEED',  '20 Jurong Port Road',              'S(619094)', '2026-08-03 11:00:00', '2026-08-03 15:00:00'),
  (4, (SELECT id FROM users WHERE name = 'Alex Tan'), '2026-07-20', '2026-07-22', 900.00,  'CONVERTED','12 Commercial Avenue, Marina South','S(018982)', '2026-07-15 10:30:00', '2026-07-15 10:30:00'),
  (5, (SELECT id FROM users WHERE name = 'Alex Tan'), '2026-08-10', '2026-08-14', 1440.00, 'CONVERTED','20 Jurong Port Road',              'S(619094)', '2026-08-01 09:00:00', '2026-08-01 09:00:00'),
  (6, (SELECT id FROM users WHERE name = 'Alex Tan'), '2026-10-05', '2026-10-09', 2400.00, 'DRAFT',    '5 Tampines Industrial Ave',        'S(528896)', '2026-08-04 09:00:00', '2026-08-04 09:00:00');

-- ============================================================
-- 2. rental_plan_records
-- ============================================================
INSERT INTO rental_plan_records (id, rental_plan_id, asset_id, daily_rate, subtotal) VALUES
  (1, 1, (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        210.00, 1050.00),
  (2, 2, (SELECT id FROM assets WHERE name = 'Genie GS-1930 Scissor Lift'), 120.00, 480.00),
  (3, 3, (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        210.00, 840.00),
  (4, 3, (SELECT id FROM assets WHERE name = 'Toyota 8FD25 Forklift'),      150.00, 600.00),
  (5, 4, (SELECT id FROM assets WHERE name = 'CAT 320 Excavator'),          450.00, 900.00),
  (6, 5, (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        210.00, 840.00),
  (7, 5, (SELECT id FROM assets WHERE name = 'Toyota 8FD25 Forklift'),      150.00, 600.00),
  (8, 6, (SELECT id FROM assets WHERE name = 'Toyota 8FD25 Forklift'),      150.00, 600.00),
  (9, 6, (SELECT id FROM assets WHERE name = 'CAT 320 Excavator'),          450.00, 1800.00);

-- ============================================================
-- 3. bookings
-- ============================================================
INSERT INTO bookings (id, customer_id, rental_plan_id, start_date, end_date, status, total_amount, deposit_amount, remaining_balance, paid_status, site_address, site_postal_code, delivery_notes, created_at) VALUES
  (1,  (SELECT id FROM users WHERE name = 'Alex Tan'), 5,    '2026-08-10', '2026-08-14', 'CONFIRMED', 1440.00, 432.00, 1008.00, 'DEPOSIT', '20 Jurong Port Road',               'S(619094)', '',                                                         '2026-08-01 09:00:00'),
  (2,  (SELECT id FROM users WHERE name = 'Alex Tan'), 4,    '2026-07-20', '2026-07-22', 'COMPLETED', 900.00,  270.00, 0.00,    'FULL',    '12 Commercial Avenue, Marina South','S(018982)', '',                                                         '2026-07-15 10:30:00'),
  (3,  (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-08-18', '2026-08-21', 'PENDING',   360.00,  108.00, 252.00,  'DEPOSIT', '15 Pioneer Sector 1',               'S(628413)', 'Access via loading bay B, coordinate with site security', '2026-08-05 14:00:00'),
  (4,  (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-08-01', '2026-08-05', 'MOBILISED', 840.00,  252.00, 588.00,  'DEPOSIT', '88 Tuas South Ave 3',               'S(637311)', 'Crane assist required for offload',                        '2026-07-25 08:50:00'),
  (5,  (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-09-01', '2026-09-06', 'CONFIRMED', 2350.00, 705.00, 1645.00, 'DEPOSIT', '5 Tampines Industrial Ave',         'S(528896)', '',                                                         '2026-08-20 11:00:00'),
  (6,  (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-07-01', '2026-07-03', 'CANCELLED', 300.00,  0.00,   300.00,  'UNPAID',  '10 Woodlands Ave 2',                'S(738068)', 'Customer cancelled prior to mobilisation',                 '2026-06-25 09:00:00'),
  (7,  (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-06-10', '2026-06-12', 'COMPLETED', 240.00,  72.00,  0.00,    'FULL',    '20 Jurong Port Road',               'S(619094)', '',                                                         '2026-06-01 09:00:00'),
  (8,  (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-05-15', '2026-05-18', 'COMPLETED', 630.00,  189.00, 0.00,    'FULL',    '22 Kranji Way',                     'S(739450)', '',                                                         '2026-05-01 09:00:00'),
  (9,  (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-04-10', '2026-04-12', 'COMPLETED', 320.00,  96.00,  0.00,    'FULL',    '8 Senoko Drive',                    'S(758196)', '',                                                         '2026-03-28 09:00:00'),
  (10, (SELECT id FROM users WHERE name = 'Alex Tan'), NULL, '2026-02-05', '2026-02-07', 'COMPLETED', 900.00,  270.00, 0.00,    'FULL',    '3 Benoi Road',                      'S(629895)', '',                                                         '2026-01-25 09:00:00');

-- ============================================================
-- 4. booking_items
-- ============================================================
INSERT INTO booking_items (id, booking_id, asset_id, daily_rate, subtotal, start_engine_hours, end_engine_hours, initial_condition, return_condition) VALUES
  (1,  1,  (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        210.00, 840.00,  NULL,   NULL,   NULL,        NULL),
  (2,  1,  (SELECT id FROM assets WHERE name = 'Toyota 8FD25 Forklift'),      150.00, 600.00,  NULL,   NULL,   NULL,        NULL),
  (3,  2,  (SELECT id FROM assets WHERE name = 'CAT 320 Excavator'),          450.00, 900.00,  1500.0, 1524.0, 'GOOD',      'FAIR'),
  (4,  3,  (SELECT id FROM assets WHERE name = 'Genie GS-1930 Scissor Lift'), 120.00, 360.00,  NULL,   NULL,   NULL,        NULL),
  (5,  4,  (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        210.00, 840.00,  160.0,  NULL,   'GOOD',      NULL),
  (6,  5,  (SELECT id FROM assets WHERE name = 'Komatsu PC210 Excavator'),    470.00, 2350.00, NULL,   NULL,   NULL,        NULL),
  (7,  6,  (SELECT id FROM assets WHERE name = 'Toyota 8FD25 Forklift'),      150.00, 300.00,  NULL,   NULL,   NULL,        NULL),
  (8,  7,  (SELECT id FROM assets WHERE name = 'Genie GS-1930 Scissor Lift'), 120.00, 240.00,  250.0,  266.0,  'GOOD',      'GOOD'),
  (9,  8,  (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        210.00, 630.00,  90.0,   114.0,  'EXCELLENT', 'GOOD'),
  (10, 9,  (SELECT id FROM assets WHERE name = 'Hyster H2.5FT Forklift'),     160.00, 320.00,  700.0,  716.0,  'GOOD',      'GOOD'),
  (11, 10, (SELECT id FROM assets WHERE name = 'CAT 320 Excavator'),          450.00, 900.00,  2000.0, 2024.0, 'FAIR',      'NEEDS_REPAIR');

-- ============================================================
-- 5. payments
-- ============================================================
INSERT INTO payments (id, booking_id, stripe_payment_intent_id, stripe_charge_id, stripe_customer_id, amount, payment_type, status, failure_reason, paid_at, created_at) VALUES
  (1,  1,  'pi_3PQb1FKx9x1x1x1x1', 'ch_3PQb1FKx9x1a1', 'cus_AlexTan001', 432.00, 'DEPOSIT',      'SUCCESS', NULL,                                 '2026-08-01 09:15:00', '2026-08-01 09:10:00'),
  (2,  2,  'pi_3PQa2FKx9x1x1x1x2', 'ch_3PQa2FKx9x1a2', 'cus_AlexTan001', 270.00, 'DEPOSIT',      'SUCCESS', NULL,                                 '2026-07-15 10:35:00', '2026-07-15 10:30:00'),
  (3,  2,  'pi_3PQa3FKx9x1x1x1x3', 'ch_3PQa3FKx9x1a3', 'cus_AlexTan001', 630.00, 'BALANCE',      'SUCCESS', NULL,                                 '2026-07-19 16:00:00', '2026-07-19 15:50:00'),
  (4,  3,  'pi_3PQa4FKx9x1x1x1x4', 'ch_3PQa4FKx9x1a4', 'cus_AlexTan001', 108.00, 'DEPOSIT',      'SUCCESS', NULL,                                 '2026-08-05 14:10:00', '2026-08-05 14:00:00'),
  (5,  4,  'pi_3PQa5FKx9x1x1x1x5', 'ch_3PQa5FKx9x1a5', 'cus_AlexTan001', 252.00, 'DEPOSIT',      'SUCCESS', NULL,                                 '2026-07-25 09:00:00', '2026-07-25 08:50:00'),
  (6,  5,  'pi_3PQa6FKx9x1x1x1x6', 'ch_3PQa6FKx9x1a6', 'cus_AlexTan001', 705.00, 'DEPOSIT',      'SUCCESS', NULL,                                 '2026-08-20 11:10:00', '2026-08-20 11:00:00'),
  (7,  6,  'pi_3PQa7FKx9x1x1x1x7', NULL,                'cus_AlexTan001', 90.00,  'DEPOSIT',      'FAIL',    'Card declined - insufficient funds', NULL,                   '2026-06-25 09:05:00'),
  (8,  7,  'pi_3PQa8FKx9x1x1x1x8', 'ch_3PQa8FKx9x1a8', 'cus_AlexTan001', 240.00, 'FULL_PAYMENT', 'SUCCESS', NULL,                                 '2026-06-01 09:20:00', '2026-06-01 09:15:00'),
  (9,  8,  'pi_3PQa9FKx9x1x1x1x9', 'ch_3PQa9FKx9x1a9', 'cus_AlexTan001', 630.00, 'FULL_PAYMENT', 'SUCCESS', NULL,                                 '2026-05-01 09:30:00', '2026-05-01 09:25:00'),
  (10, 9,  'pi_3PQb0FKx9x1x1x1x0', 'ch_3PQb0FKx9x1b0', 'cus_AlexTan001', 320.00, 'FULL_PAYMENT', 'SUCCESS', NULL,                                 '2026-03-28 09:20:00', '2026-03-28 09:15:00'),
  (11, 10, 'pi_3PQb1FKx9x1x1x1b1', 'ch_3PQb1FKx9x1b1', 'cus_AlexTan001', 900.00, 'FULL_PAYMENT', 'SUCCESS', NULL,                                 '2026-02-05 09:10:00', '2026-02-05 09:00:00');

-- ============================================================
-- 6. delivery_records
-- ============================================================
INSERT INTO delivery_records (id, booking_id, driver_id, delivered_at, delivery_photos, customer_signature_url) VALUES
  (1, 2,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-07-20 08:30:00', 'https://cdn.example.sg/delivery/booking2-1.jpg',  'https://cdn.example.sg/signatures/booking2-delivery.png'),
  (2, 4,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-08-01 09:00:00', 'https://cdn.example.sg/delivery/booking4-1.jpg',  'https://cdn.example.sg/signatures/booking4-delivery.png'),
  (3, 7,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-06-10 08:15:00', 'https://cdn.example.sg/delivery/booking7-1.jpg',  'https://cdn.example.sg/signatures/booking7-delivery.png'),
  (4, 8,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-05-15 08:00:00', 'https://cdn.example.sg/delivery/booking8-1.jpg',  'https://cdn.example.sg/signatures/booking8-delivery.png'),
  (5, 9,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-04-10 08:30:00', 'https://cdn.example.sg/delivery/booking9-1.jpg',  'https://cdn.example.sg/signatures/booking9-delivery.png'),
  (6, 10, (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-02-05 08:00:00', 'https://cdn.example.sg/delivery/booking10-1.jpg', 'https://cdn.example.sg/signatures/booking10-delivery.png');

-- ============================================================
-- 7. return_records
-- ============================================================
INSERT INTO return_records (id, booking_id, driver_id, returned_at, return_photos, customer_signature_url) VALUES
  (1, 2,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-07-22 17:00:00', 'https://cdn.example.sg/returns/booking2-1.jpg',  'https://cdn.example.sg/signatures/booking2-return.png'),
  (2, 7,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-06-12 17:30:00', 'https://cdn.example.sg/returns/booking7-1.jpg',  'https://cdn.example.sg/signatures/booking7-return.png'),
  (3, 8,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-05-18 17:00:00', 'https://cdn.example.sg/returns/booking8-1.jpg',  'https://cdn.example.sg/signatures/booking8-return.png'),
  (4, 9,  (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-04-12 16:45:00', 'https://cdn.example.sg/returns/booking9-1.jpg',  'https://cdn.example.sg/signatures/booking9-return.png'),
  (5, 10, (SELECT id FROM users WHERE name = 'Ah Tan'), '2026-02-07 17:15:00', 'https://cdn.example.sg/returns/booking10-1.jpg', 'https://cdn.example.sg/signatures/booking10-return.png');

-- ============================================================
-- 8. ai_recommendations
-- ============================================================
INSERT INTO ai_recommendations (id, user_id, confidence_score, status, previous_recommendation_id, raw_project_prompt, document_url, ai_reasoning_summary, created_at) VALUES
  (1, (SELECT id FROM users WHERE name = 'Alex Tan'),   0.87, 'ACCEPTED',  NULL, 'Need to refurbish a 5-storey walk-up in Toa Payoh - exterior repainting and gutter cleaning, ceiling height up to 15m, tight lane for access.', 'https://docs.example.sg/ai-recs/rec-1.pdf', 'Recommended a compact telescopic boom lift for the 15m reach with narrow-lane maneuverability; scissor lift ruled out due to uneven ground access.', '2026-07-10 10:00:00'),
  (2, (SELECT id FROM users WHERE name = 'Alex Tan'),   0.65, 'GENERATED', NULL, 'Warehouse racking installation in Tuas, indoor only, need to move palletised steel racking components across a 2000 sqm floor.', 'https://docs.example.sg/ai-recs/rec-2.pdf', 'Suggested a counterbalance forklift for indoor pallet handling capacity matching the racking component weight.', '2026-07-18 14:30:00'),
  (3, (SELECT id FROM users WHERE name = 'Alex Tan'),   0.72, 'REJECTED',  NULL, 'Foundation excavation for a new mixed-use development in Marina South, need to trench for utility lines across a 40m stretch.', 'https://docs.example.sg/ai-recs/rec-3.pdf', 'Proposed a mid-size hydraulic excavator based on trench depth and soil condition estimates from the project brief.', '2026-07-05 09:00:00'),
  (4, (SELECT id FROM users WHERE name = 'Alex Tan'),   0.79, 'GENERATED', 3,    'Updated scope: foundation excavation in Marina South now includes demolition of an existing slab prior to trenching.', 'https://docs.example.sg/ai-recs/rec-4.pdf', 'Revised recommendation upgrades to a larger-capacity excavator given the added demolition scope and higher bucket-force requirement.', '2026-07-06 11:00:00'),
  (5, (SELECT id FROM users WHERE name = 'Ravi Kumar'), 0.55, 'EXPIRED',   NULL, 'Internal test: evaluating AI recommendation quality for a hypothetical Pioneer Sector fit-out project requiring indoor elevated access.', NULL, 'Low-confidence exploratory recommendation generated for internal QA; suggested a compact scissor lift for indoor fit-out access.', '2026-06-15 16:00:00');

-- ============================================================
-- 9. recommendation_items
-- ============================================================
INSERT INTO recommendation_items (id, recommendation_id, asset_id, rank_order, match_score, ml_predicted_price) VALUES
  (1, 1, (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        1, 0.91, 205.00),
  (2, 1, (SELECT id FROM assets WHERE name = 'Genie GS-1930 Scissor Lift'), 2, 0.58, 115.00),
  (3, 2, (SELECT id FROM assets WHERE name = 'Toyota 8FD25 Forklift'),      1, 0.88, 145.00),
  (4, 3, (SELECT id FROM assets WHERE name = 'CAT 320 Excavator'),          1, 0.74, 440.00),
  (5, 4, (SELECT id FROM assets WHERE name = 'Komatsu PC210 Excavator'),    1, 0.83, 460.00),
  (6, 4, (SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift'),        2, 0.40, 215.00),
  (7, 5, (SELECT id FROM assets WHERE name = 'JLG 2630ES Scissor Lift'),    1, 0.61, 135.00),
  (8, 5, (SELECT id FROM assets WHERE name = 'Hyster H2.5FT Forklift'),     2, 0.30, 155.00);
