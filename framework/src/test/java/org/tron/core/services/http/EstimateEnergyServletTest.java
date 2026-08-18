package org.tron.core.services.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;

public class EstimateEnergyServletTest extends BaseHttpTest {

  private static final String ISSUE_OWNER = "TKgD8Qnx9Zw3RNjdiU2i5y2Swa2y4QvG6v";
  private static final String USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
  private static final String ODD_HEX =
      "0000000000000000000000418a8e8b8c8d8e8f9a9b9c9d9e9f0a1b2c3d4e5f6";

  private EstimateEnergyServlet servlet;

  @Override
  protected void setUpMocks() throws Exception {
    servlet = new EstimateEnergyServlet();
    injectWallet(servlet);
  }

  @Test
  public void testInvalidBase58CheckOwnerReturnsStableError() throws Exception {
    String body = "{\"owner_address\":\"" + ISSUE_OWNER + "\","
        + "\"contract_address\":\"" + USDT + "\","
        + "\"function_selector\":\"isBlackListed(address)\","
        + "\"parameter\":\"00\","
        + "\"visible\":true}";
    MockHttpServletResponse response = newResponse();
    servlet.doPost(postRequest(body), response);
    assertEquals(200, response.getStatus());
    String content = response.getContentAsString();
    assertTrue(content.contains(Util.INVALID_ADDRESS_BASE58CHECK));
    assertFalse(content.contains("NullPointerException"));
    assertFalse(content.contains("java.lang"));
    verify(wallet, never()).estimateEnergy(any(), any(), any(), any(), any());
  }

  @Test
  public void testOddLengthHexParameterReturnsStableError() throws Exception {
    String owner = ByteArray.toHexString(new ECKey().getAddress());
    String contract = ByteArray.toHexString(new ECKey().getAddress());
    String body = "{\"owner_address\":\"" + owner + "\","
        + "\"contract_address\":\"" + contract + "\","
        + "\"function_selector\":\"isBlackListed(address)\","
        + "\"parameter\":\"" + ODD_HEX + "\"}";
    MockHttpServletResponse response = newResponse();
    servlet.doPost(postRequest(body), response);
    assertEquals(200, response.getStatus());
    String content = response.getContentAsString();
    assertTrue(content.contains(Util.INVALID_HEX_LENGTH));
    assertFalse(content.contains("NullPointerException"));
    verify(wallet, never()).estimateEnergy(any(), any(), any(), any(), any());
  }

  @Test
  public void testInternalErrorDoesNotLeakExceptionDetails() throws Exception {
    String owner = ByteArray.toHexString(new ECKey().getAddress());
    String contract = ByteArray.toHexString(new ECKey().getAddress());
    String body = "{\"owner_address\":\"" + owner + "\","
        + "\"contract_address\":\"" + contract + "\","
        + "\"data\":\"00\"}";
    when(wallet.createTransactionCapsule(any(), any()))
        .thenThrow(new NullPointerException("secret internals"));
    MockHttpServletResponse response = newResponse();
    servlet.doPost(postRequest(body), response);
    assertEquals(200, response.getStatus());
    String content = response.getContentAsString();
    assertTrue(content.contains(Util.INTERNAL_ERROR_MSG));
    assertFalse(content.contains("NullPointerException"));
    assertFalse(content.contains("secret internals"));
  }
}
