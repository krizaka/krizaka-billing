package com.orazaka.billing.domain.exception;

/**
 * A pack's price or grants could not be written.
 *
 * <p>Unchecked and never swallowed. The alternative — logging and continuing — produces a catalogue
 * entry promising an entitlement that billing cannot grant, which surfaces as a customer who has
 * paid and is still locked out (ADR-036, invariant #3).
 */
public class PackProvisioningException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which pack could not be provisioned
   * @param cause the transport failure underneath
   */
  public PackProvisioningException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates the exception with no underlying cause.
   *
   * @param message which pack could not be provisioned
   */
  public PackProvisioningException(String message) {
    super(message);
  }
}
