package org.tron.common.zksnark;

import java.nio.ByteBuffer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.context.GlobalContext;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.common.zksnark.LibrustzcashParam.BindingSigParams;
import org.tron.common.zksnark.LibrustzcashParam.CheckOutputNewParams;
import org.tron.common.zksnark.LibrustzcashParam.CheckOutputParams;
import org.tron.common.zksnark.LibrustzcashParam.CheckSpendNewParams;
import org.tron.common.zksnark.LibrustzcashParam.CheckSpendParams;
import org.tron.common.zksnark.LibrustzcashParam.ComputeCmParams;
import org.tron.common.zksnark.LibrustzcashParam.ComputeNfParams;
import org.tron.common.zksnark.LibrustzcashParam.CrhIvkParams;
import org.tron.common.zksnark.LibrustzcashParam.FinalCheckNewParams;
import org.tron.common.zksnark.LibrustzcashParam.FinalCheckParams;
import org.tron.common.zksnark.LibrustzcashParam.InitZksnarkParams;
import org.tron.common.zksnark.LibrustzcashParam.IvkToPkdParams;
import org.tron.common.zksnark.LibrustzcashParam.KaAgreeParams;
import org.tron.common.zksnark.LibrustzcashParam.KaDerivepublicParams;
import org.tron.common.zksnark.LibrustzcashParam.MerkleHashParams;
import org.tron.common.zksnark.LibrustzcashParam.OutputProofParams;
import org.tron.common.zksnark.LibrustzcashParam.SpendProofParams;
import org.tron.common.zksnark.LibrustzcashParam.SpendSigParams;
import org.tron.common.zksnark.LibrustzcashParam.Zip32XfvkAddressParams;
import org.tron.common.zksnark.LibrustzcashParam.Zip32XskDeriveParams;
import org.tron.common.zksnark.LibrustzcashParam.Zip32XskMasterParams;
import org.tron.core.exception.ZksnarkException;

@Slf4j(topic = "zcash")
public class JLibrustzcash {

  private static Librustzcash INSTANCE;

  public static void librustzcashZip32XskMaster(Zip32XskMasterParams params) {
    if (!isOpenZen()) {
      return;
    }
    INSTANCE.librustzcashZip32XskMaster(params.getData(), params.getSize(), params.getM_bytes());
  }

  public static void librustzcashInitZksnarkParams(InitZksnarkParams params) {
    if (!isOpenZen()) {
      return;
    }
    INSTANCE.librustzcashInitZksnarkParams(params.getSpend_path(),
        params.getSpend_hash(), params.getOutput_path(), params.getOutput_hash());
  }

  public static void librustzcashZip32XskDerive(Zip32XskDeriveParams params) {
    if (!isOpenZen()) {
      return;
    }
    INSTANCE.librustzcashZip32XskDerive(params.getData(), params.getSize(), params.getM_bytes());
  }

  public static boolean librustzcashZip32XfvkAddress(Zip32XfvkAddressParams params) {
    if (!isOpenZen()) {
      return true;
    }
    return INSTANCE.librustzcashZip32XfvkAddress(params.getXfvk(), params.getJ(),
        params.getJ_ret(), params.getAddr_ret());
  }

  public static void librustzcashCrhIvk(CrhIvkParams params) {
    if (!isOpenZen()) {
      return;
    }
    INSTANCE.librustzcashCrhIvk(params.getAk(), params.getNk(), params.getIvk());
    log(4, params.getAk(), params.getNk(), params.getIvk());
  }

  public static boolean librustzcashKaAgree(KaAgreeParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingKaAgree(params.getP(),
        params.getSk(), params.getResult());
    log(5, params.getP(), params.getSk(), params.getResult(), b2B(res));
    return res;
  }

  public static boolean librustzcashComputeCm(ComputeCmParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingComputeCm(params.getD(), params.getPkD(),
        params.getValue(), params.getR(), params.getCm());
    log(8, params.getD(), params.getPkD(), l2B(params.getValue()), params.getR(), params.getCm(),
        b2B(res));
    return res;
  }

  public static boolean librustzcashComputeNf(ComputeNfParams params) {
    if (isOpenZen()) {
      INSTANCE.librustzcashSaplingComputeNf(params.getD(), params.getPkD(), params.getValue(),
              params.getR(), params.getAk(), params.getNk(), params.getPosition(), params.getResult());
      log(1, params.getD(), params.getPkD(), l2B(params.getValue()), params.getR(),
          params.getAk(), params.getNk(), l2B(params.getPosition()), params.getResult());
    }
    return true;
  }

  /**
   * @param ask the spend authorizing key,to generate ak, 32 bytes
   * @return ak 32 bytes
   */
  public static byte[] librustzcashAskToAk(byte[] ask) throws ZksnarkException {
    if (!isOpenZen()) {
      return ByteUtil.EMPTY_BYTE_ARRAY;
    }
    LibrustzcashParam.valid32Params(ask);
    byte[] ak = new byte[32];
    INSTANCE.librustzcashAskToAk(ask, ak);
    log(0, ask, ak);
    return ak;
  }

  /**
   * @param nsk the proof authorizing key, to generate nk, 32 bytes
   * @return 32 bytes
   */
  public static byte[] librustzcashNskToNk(byte[] nsk) throws ZksnarkException {
    if (!isOpenZen()) {
      return ByteUtil.EMPTY_BYTE_ARRAY;
    }
    LibrustzcashParam.valid32Params(nsk);
    byte[] nk = new byte[32];
    INSTANCE.librustzcashNskToNk(nsk, nk);
    log(2, nsk, nk);
    return nk;
  }

  // void librustzcash_nsk_to_nk(const unsigned char *nsk, unsigned char *result);

  /**
   * @return r: random number, less than r_J,   32 bytes
   */
  public static byte[] librustzcashSaplingGenerateR(byte[] r) throws ZksnarkException {
    if (!isOpenZen()) {
      return ByteUtil.EMPTY_BYTE_ARRAY;
    }
    LibrustzcashParam.valid32Params(r);
    INSTANCE.librustzcashSaplingGenerateR(r);
    return r;
  }

  public static boolean librustzcashSaplingKaDerivepublic(KaDerivepublicParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingKaDerivepublic(params.getDiversifier(),
        params.getEsk(), params.getResult());
    log(3, params.getDiversifier(), params.getEsk(), params.getResult(), b2B(res));
    return res;
  }

  public static long librustzcashSaplingProvingCtxInit() {
    if (!isOpenZen()) {
      return 0;
    }
    return INSTANCE.librustzcashSaplingProvingCtxInit();
  }

  /**
   * check validity of d
   *
   * @param d 11 bytes
   */
  public static boolean librustzcashCheckDiversifier(byte[] d) throws ZksnarkException {
    if (!isOpenZen()) {
      return true;
    }
    LibrustzcashParam.valid11Params(d);
    boolean res = INSTANCE.librustzcashCheckDiversifier(d);
    log(6, d, b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingSpendProof(SpendProofParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingSpendProof(params.getCtx(), params.getAk(),
        params.getNsk(), params.getD(), params.getR(), params.getAlpha(), params.getValue(),
        params.getAnchor(), params.getVoucherPath(), params.getCv(), params.getRk(),
        params.getZkproof());
    log(9, params.getAk(), params.getNsk(), params.getD(), params.getR(),
        params.getAlpha(), l2B(params.getValue()), params.getAnchor(), params.getVoucherPath(),
        params.getCv(), params.getRk(), params.getZkproof(), b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingOutputProof(OutputProofParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingOutputProof(params.getCtx(), params.getEsk(),
        params.getD(), params.getPkD(), params.getR(), params.getValue(), params.getCv(),
        params.getZkproof());
    log(10, params.getEsk(), params.getD(), params.getPkD(), params.getR(),
        l2B(params.getValue()), params.getCv(), params.getZkproof(), b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingSpendSig(SpendSigParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingSpendSig(params.getAsk(), params.getAlpha(),
        params.getSigHash(), params.getResult());
    log(11, params.getAsk(), params.getAlpha(), params.getSigHash(), params.getResult(), b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingBindingSig(BindingSigParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingBindingSig(params.getCtx(),
        params.getValueBalance(), params.getSighash(), params.getResult());
    log(12, l2B(params.getValueBalance()), params.getSighash(), params.getResult(), b2B(res));
    return res;
  }

  /**
   * convert value to 32-byte scalar
   *
   * @param value 64 bytes
   * @param data 32 bytes
   */
  public static void librustzcashToScalar(byte[] value, byte[] data) throws ZksnarkException {
    if (!isOpenZen()) {
      return;
    }
    LibrustzcashParam.validParamLength(value, 64);
    LibrustzcashParam.valid32Params(data);
    INSTANCE.librustzcashToScalar(value, data);
    log(18, value, data);
  }

  public static void librustzcashSaplingProvingCtxFree(long ctx) {
    if (!isOpenZen()) {
      return;
    }
    INSTANCE.librustzcashSaplingProvingCtxFree(ctx);
  }

  public static long librustzcashSaplingVerificationCtxInit() {
    if (!isOpenZen()) {
      return 0;
    }
    return INSTANCE.librustzcashSaplingVerificationCtxInit();
  }

  public static boolean librustzcashSaplingCheckSpend(CheckSpendParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingCheckSpend(params.getCtx(), params.getCv(),
        params.getAnchor(), params.getNullifier(), params.getRk(), params.getZkproof(),
        params.getSpendAuthSig(), params.getSighashValue());
    log(13, params.getCv(), params.getAnchor(), params.getNullifier(), params.getRk(),
        params.getZkproof(), params.getSpendAuthSig(), params.getSighashValue(), b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingCheckOutput(CheckOutputParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingCheckOutput(params.getCtx(), params.getCv(),
        params.getCm(), params.getEphemeralKey(), params.getZkproof());
    log(14, params.getCv(), params.getCm(), params.getEphemeralKey(), params.getZkproof(),
        b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingFinalCheck(FinalCheckParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingFinalCheck(params.getCtx(),
        params.getValueBalance(), params.getBindingSig(), params.getSighashValue());
    log(15, l2B(params.getValueBalance()), params.getBindingSig(), params.getSighashValue(),
        b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingCheckSpendNew(CheckSpendNewParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingCheckSpendNew(params.getCv(),
        params.getAnchor(), params.getNullifier(), params.getRk(), params.getZkproof(),
        params.getSpendAuthSig(), params.getSighashValue());
    log(19, params.getCv(), params.getAnchor(), params.getNullifier(), params.getRk(),
        params.getZkproof(), params.getSpendAuthSig(), params.getSighashValue(), b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingCheckOutputNew(CheckOutputNewParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashSaplingCheckOutputNew(params.getCv(), params.getCm(),
        params.getEphemeralKey(), params.getZkproof());
    log(20, params.getCv(), params.getCm(), params.getEphemeralKey(), params.getZkproof(), b2B(res));
    return res;
  }

  public static boolean librustzcashSaplingFinalCheckNew(FinalCheckNewParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE
        .librustzcashSaplingFinalCheckNew(params.getValueBalance(), params.getBindingSig(),
            params.getSighashValue(), params.getSpendCv(), params.getSpendCvLen(),
            params.getOutputCv(), params.getOutputCvLen());
    log(21, l2B(params.getValueBalance()), params.getBindingSig(), params.getSighashValue(),
        params.getSpendCv(), i2B(params.getSpendCvLen()),
        params.getOutputCv(), i2B(params.getOutputCvLen()), b2B(res));
    return res;
  }

  public static void librustzcashSaplingVerificationCtxFree(long ctx) {
    if (!isOpenZen()) {
      return;
    }
    INSTANCE.librustzcashSaplingVerificationCtxFree(ctx);
  }

  public static boolean librustzcashIvkToPkd(IvkToPkdParams params) {
    if (!isOpenZen()) {
      return true;
    }
    boolean res = INSTANCE.librustzcashIvkToPkd(params.getIvk(), params.getD(), params.getPkD());
    log(7, params.getIvk(), params.getD(), params.getPkD(), b2B(res));
    return res;
  }

  public static void librustzcashMerkleHash(MerkleHashParams params) {
    if (!isOpenZen()) {
      return;
    }
    INSTANCE.librustzcashMerkleHash(params.getDepth(), params.getA(), params.getB(),
        params.getResult());
    log(16, l2B(params.getDepth()), params.getA(), params.getB(), params.getResult());
  }

  /**
   * @param result uncommitted value, 32 bytes
   */
  public static void librustzcashTreeUncommitted(byte[] result) throws ZksnarkException {
    if (!isOpenZen()) {
      return;
    }
    LibrustzcashParam.valid32Params(result);
    INSTANCE.librustzcashTreeUncommitted(result);
    log(17, result);
  }

  public static boolean isOpenZen() {
    boolean res = CommonParameter.getInstance().isFullNodeAllowShieldedTransactionArgs();
    if (res) {
      INSTANCE = LibrustzcashWrapper.getInstance();
    }
    return res;
  }

  private static void log(int c, byte[] ... params) {
    if (GlobalContext.isLog()) {
      GlobalContext.getHeader().ifPresent(header -> logger.info("{}:{}:{}", header, c,
          Stream.of(params).map(ByteArray::toHexString).collect(Collectors.joining(","))));
    }
  }

  private static byte[] l2B(long value) {
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    buffer.putLong(value);
    return buffer.array();
  }

  private static byte[] i2B(int value) {
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
    buffer.putInt(value);
    return buffer.array();
  }

  private static byte[] b2B(boolean b) {
    return b ? new byte[]{1} : new byte[]{0};
  }

}
