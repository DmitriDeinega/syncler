"""V4 #20 — plugin sensitivity declaration

Adds a non-null TEXT column ``sensitivity`` to ``plugins``
carrying the plugin-author-declared sensitivity level. Values:
``public`` (default; the plugin's content can be opened without
re-authentication) or ``sensitive`` (the Android app gates card
opens behind a biometric/device-credential/password prompt and
renders a "🔒 Locked" placeholder in the inbox until unlocked).

Server stores opaquely; enforcement is entirely client-side
because the server is content-blind. The publish-time signature
envelope conditionally includes the field only when it's not
the default ("public"), so legacy senders that don't know about
this field continue to sign over byte-identical envelopes.

Spec: triad 166 agreement.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0015_plugin_sensitivity"
down_revision = "0014_card_patches"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "plugins",
        sa.Column(
            "sensitivity",
            sa.Text(),
            nullable=False,
            server_default=sa.text("'public'"),
        ),
    )


def downgrade() -> None:
    op.drop_column("plugins", "sensitivity")
