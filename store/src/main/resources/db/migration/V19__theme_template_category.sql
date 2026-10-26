-- ============================================================================
-- Appearance catalogue: a category for every template
--
-- <p>The Themes gallery now offers a shelf of ready-made designs across verticals — grocery,
-- fashion, electronics, café, beauty, jewellery and so on — rather than one generic layout. A
-- shop owner picks by recognising their own trade, so the shelf needs to say what each template
-- is *for*.
--
-- <p><b>Why a new column and not {@code shop_type}.</b> {@code shop_type} is a filter: a template
-- tagged PHARMACY is deliberately invisible to a bakery, which is right for a clinical layout and
-- wrong for a catalogue. A grocery owner may legitimately want the fashion theme, and this
-- platform should not decide otherwise. So {@code category} is a label for people, while
-- {@code shop_type} stays a rule for the marketplace query. Keeping them in one column would have
-- meant choosing between "everyone can see everything" and "you only see your own trade".
--
-- <p>Nullable by design. {@code NULL} means "no category yet", which is exactly true of a template
-- an admin creates before deciding where it belongs; the gallery groups those under whatever the
-- API returns rather than inventing a label for them.
-- ============================================================================

ALTER TABLE theme_template
    ADD COLUMN IF NOT EXISTS category text;

COMMENT ON COLUMN theme_template.category IS
    'Human-facing shelf label in the Themes gallery ("Grocery", "Fashion"). Not a filter rule — '
    'shop_type does that. NULL means the template has not been categorised yet.';

-- The two templates that predate the catalogue, so the first page a shop owner sees is complete
-- rather than half-labelled. Only fills blanks: an admin who has already chosen a category keeps
-- it.
UPDATE theme_template
SET category = 'Pharmacy'
WHERE code = 'pharmacy-trust'
  AND category IS NULL;

UPDATE theme_template
SET category = 'General'
WHERE code = 'general-storefront'
  AND category IS NULL;
