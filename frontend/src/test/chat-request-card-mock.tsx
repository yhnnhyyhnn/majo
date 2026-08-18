import React from "react";

/**
 * Test stub for the vendor Request Card deep import used by HostBubbles.
 * Renders children so ChatPage tests can exercise bubble layout.
 */
const VendorRequestCard: React.FC<{ children?: React.ReactNode }> = ({
  children,
}) => <div data-testid="vendor-request-card">{children}</div>;

export default VendorRequestCard;
