package org.tron.core.capsule;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.zksnark.JLibrustzcash;
import org.tron.common.zksnark.LibrustzcashParam.MerkleHashParams;
import org.tron.core.exception.ZksnarkException;
import org.tron.protos.contract.ShieldContract.PedersenHash;


@Slf4j
public class PedersenHashCapsule implements ProtoCapsule<PedersenHash> {

  private PedersenHash pedersenHash;

  public PedersenHashCapsule() {
    this.pedersenHash = PedersenHash.getDefaultInstance();
  }

  public PedersenHashCapsule(final PedersenHash pedersenHash) {
    this.pedersenHash = pedersenHash;
  }

  public PedersenHashCapsule(final byte[] data) {
    try {
      this.pedersenHash = PedersenHash.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
    }
  }

  public static PedersenHashCapsule combine(final PedersenHash a, final PedersenHash b, int depth)
      throws ZksnarkException {
    byte[] res = new byte[32];

    JLibrustzcash.librustzcashMerkleHash(new MerkleHashParams(depth, a.getContent().toByteArray(),
        b.getContent().toByteArray(), res));

    PedersenHashCapsule pedersenHashCapsule = new PedersenHashCapsule();
    pedersenHashCapsule.setContent(ByteString.copyFrom(res));

    return pedersenHashCapsule;
  }

  public static PedersenHashCapsule uncommitted() throws ZksnarkException {
    byte[] res = new byte[32];

    JLibrustzcash.librustzcashTreeUncommitted(res);

    PedersenHashCapsule compressCapsule = new PedersenHashCapsule();
    compressCapsule.setContent(ByteString.copyFrom(res));

    return compressCapsule;
  }

  public ByteString getContent() {
    return this.pedersenHash.getContent();
  }

  public void setContent(ByteString content) {
    this.pedersenHash = PedersenHash.newBuilder().setContent(content).build();
  }

  @Override
  public byte[] getData() {
    return this.pedersenHash.toByteArray();
  }

  @Override
  public PedersenHash getInstance() {
    return this.pedersenHash;
  }

  public boolean isPresent() {
    return !pedersenHash.getContent().isEmpty();
  }
}
