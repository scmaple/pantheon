/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.consensus.ibft.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tech.pegasys.pantheon.consensus.ibft.ConsensusRoundIdentifier;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.MessageFactory;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.PreparePayload;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.PreparedCertificate;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.RoundChangePayload;
import tech.pegasys.pantheon.consensus.ibft.ibftmessagedata.SignedData;
import tech.pegasys.pantheon.consensus.ibft.validation.RoundChangeMessageValidator.MessageValidatorFactory;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.Util;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;

public class RoundChangeMessageValidatorTest {

  private final KeyPair proposerKey = KeyPair.generate();
  private final KeyPair validatorKey = KeyPair.generate();
  private final KeyPair nonValidatorKey = KeyPair.generate();
  private final MessageFactory proposerMessageFactory = new MessageFactory(proposerKey);
  private final MessageFactory validatorMessageFactory = new MessageFactory(validatorKey);
  private final MessageFactory nonValidatorMessageFactory = new MessageFactory(nonValidatorKey);

  private final long chainHeight = 2;
  private final ConsensusRoundIdentifier currentRound =
      new ConsensusRoundIdentifier(chainHeight, 3);
  private final ConsensusRoundIdentifier targetRound = new ConsensusRoundIdentifier(chainHeight, 4);

  private final Block block = mock(Block.class);

  private final MessageValidator basicValidator = mock(MessageValidator.class);
  private final List<Address> validators = Lists.newArrayList();

  private final MessageValidatorFactory validatorFactory = mock(MessageValidatorFactory.class);
  private final RoundChangeMessageValidator validator =
      new RoundChangeMessageValidator(validatorFactory, validators, 1, chainHeight);

  @Before
  public void setup() {
    validators.add(Util.publicKeyToAddress(proposerKey.getPublicKey()));
    validators.add(Util.publicKeyToAddress(validatorKey.getPublicKey()));

    when(block.getHash()).thenReturn(Hash.fromHexStringLenient("1"));
    when(validatorFactory.createAt(any())).thenReturn(basicValidator);

    // By default, have all basic messages being valid thus any failures are attributed to logic
    // in the RoundChangeMessageValidator
    when(basicValidator.addSignedProposalPayload(any())).thenReturn(true);
    when(basicValidator.validatePrepareMessage(any())).thenReturn(true);
  }

  @Test
  public void roundChangeSentByNonValidatorFails() {
    final SignedData<RoundChangePayload> msg =
        nonValidatorMessageFactory.createSignedRoundChangePayload(targetRound, Optional.empty());
    assertThat(validator.validateMessage(msg)).isFalse();
  }

  @Test
  public void roundChangeContainingNoCertificateIsSuccessful() {
    final SignedData<RoundChangePayload> msg =
        proposerMessageFactory.createSignedRoundChangePayload(targetRound, Optional.empty());

    assertThat(validator.validateMessage(msg)).isTrue();
  }

  @Test
  public void roundChangeContainingInvalidProposalFails() {
    final PreparedCertificate prepareCertificate =
        new PreparedCertificate(
            proposerMessageFactory.createSignedProposalPayload(currentRound, block),
            Collections.emptyList());

    final SignedData<RoundChangePayload> msg =
        proposerMessageFactory.createSignedRoundChangePayload(
            targetRound, Optional.of(prepareCertificate));

    when(basicValidator.addSignedProposalPayload(any())).thenReturn(false);

    assertThat(validator.validateMessage(msg)).isFalse();
    verify(validatorFactory, times(1))
        .createAt(prepareCertificate.getProposalPayload().getPayload().getRoundIdentifier());
    verify(basicValidator, times(1))
        .addSignedProposalPayload(prepareCertificate.getProposalPayload());
    verify(basicValidator, never()).validatePrepareMessage(any());
    verify(basicValidator, never()).validateCommmitMessage(any());
  }

  @Test
  public void roundChangeContainingValidProposalButNoPrepareMessagesFails() {
    final PreparedCertificate prepareCertificate =
        new PreparedCertificate(
            proposerMessageFactory.createSignedProposalPayload(currentRound, block),
            Collections.emptyList());

    final SignedData<RoundChangePayload> msg =
        proposerMessageFactory.createSignedRoundChangePayload(
            targetRound, Optional.of(prepareCertificate));

    when(basicValidator.addSignedProposalPayload(any())).thenReturn(true);
    assertThat(validator.validateMessage(msg)).isFalse();
  }

  @Test
  public void roundChangeInvalidPrepareMessageFromProposerFails() {
    final SignedData<PreparePayload> prepareMsg =
        validatorMessageFactory.createSignedPreparePayload(currentRound, block.getHash());
    final PreparedCertificate prepareCertificate =
        new PreparedCertificate(
            proposerMessageFactory.createSignedProposalPayload(currentRound, block),
            Lists.newArrayList(prepareMsg));

    when(basicValidator.addSignedProposalPayload(any())).thenReturn(true);
    when(basicValidator.validatePrepareMessage(any())).thenReturn(false);

    final SignedData<RoundChangePayload> msg =
        proposerMessageFactory.createSignedRoundChangePayload(
            targetRound, Optional.of(prepareCertificate));

    assertThat(validator.validateMessage(msg)).isFalse();

    verify(basicValidator, times(1)).validatePrepareMessage(prepareMsg);
    verify(basicValidator, never()).validateCommmitMessage(any());
  }

  @Test
  public void roundChangeWithDifferentSequenceNumberFails() {
    final ConsensusRoundIdentifier latterRoundIdentifier =
        new ConsensusRoundIdentifier(currentRound.getSequenceNumber() + 1, 1);

    final SignedData<RoundChangePayload> msg =
        proposerMessageFactory.createSignedRoundChangePayload(
            latterRoundIdentifier, Optional.empty());

    assertThat(validator.validateMessage(msg)).isFalse();
    verify(basicValidator, never()).validatePrepareMessage(any());
  }

  @Test
  public void roundChangeWithProposalFromARoundAheadOfRoundChangeTargetFails() {
    final ConsensusRoundIdentifier futureRound =
        new ConsensusRoundIdentifier(
            currentRound.getSequenceNumber(), currentRound.getRoundNumber() + 2);

    final SignedData<PreparePayload> prepareMsg =
        validatorMessageFactory.createSignedPreparePayload(futureRound, block.getHash());
    final PreparedCertificate prepareCertificate =
        new PreparedCertificate(
            proposerMessageFactory.createSignedProposalPayload(futureRound, block),
            Lists.newArrayList(prepareMsg));

    final SignedData<RoundChangePayload> msg =
        proposerMessageFactory.createSignedRoundChangePayload(
            targetRound, Optional.of(prepareCertificate));

    assertThat(validator.validateMessage(msg)).isFalse();
    verify(validatorFactory, never()).createAt(any());
    verify(basicValidator, never()).validatePrepareMessage(prepareMsg);
    verify(basicValidator, never()).validateCommmitMessage(any());
  }

  @Test
  public void roundChangeWithPastProposalForCurrentHeightIsSuccessful() {
    final SignedData<PreparePayload> prepareMsg =
        validatorMessageFactory.createSignedPreparePayload(currentRound, block.getHash());
    final PreparedCertificate prepareCertificate =
        new PreparedCertificate(
            proposerMessageFactory.createSignedProposalPayload(currentRound, block),
            Lists.newArrayList(prepareMsg));

    final SignedData<RoundChangePayload> msg =
        proposerMessageFactory.createSignedRoundChangePayload(
            targetRound, Optional.of(prepareCertificate));

    when(basicValidator.addSignedProposalPayload(prepareCertificate.getProposalPayload()))
        .thenReturn(true);
    when(basicValidator.validatePrepareMessage(prepareMsg)).thenReturn(true);

    assertThat(validator.validateMessage(msg)).isTrue();
    verify(validatorFactory, times(1))
        .createAt(prepareCertificate.getProposalPayload().getPayload().getRoundIdentifier());
    verify(basicValidator, times(1))
        .addSignedProposalPayload(prepareCertificate.getProposalPayload());
    verify(basicValidator, times(1)).validatePrepareMessage(prepareMsg);
  }
}
