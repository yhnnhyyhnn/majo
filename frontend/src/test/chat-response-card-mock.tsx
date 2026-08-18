import React from "react";

/**
 * Test stub for the vendor Response Card deep import used by HostBubbles.
 * Renders children so ChatPage tests can exercise bubble layout.
 */
const VendorResponseCard: React.FC<{ children?: React.ReactNode }> = ({
  children,
}) => <div data-testid="vendor-response-card">{children}</div>;

export default VendorResponseCard;
