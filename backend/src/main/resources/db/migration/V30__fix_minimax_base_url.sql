-- ADR-0019 addendum: MiniMax provider rows seeded with the Anthropic-style
-- base URL build broken URLs under the OpenAI-compatible client (the SDK
-- appends /v1/chat/completions). The documented OpenAI-compatible base is
-- the bare domain — https://api.minimax.io / https://api.minimaxi.com —
-- which the client completes to /v1/chat/completions. Only untouched stock
-- values are repaired; user-customized URLs are left alone.

UPDATE providers
SET base_url = 'https://api.minimax.io',
    chat_model = 'OpenAIChatModel'
WHERE id = 'minimax'
  AND base_url = 'https://api.minimax.io/anthropic';

UPDATE providers
SET base_url = 'https://api.minimaxi.com',
    chat_model = 'OpenAIChatModel'
WHERE id = 'minimax-cn'
  AND base_url = 'https://api.minimaxi.com/anthropic';
