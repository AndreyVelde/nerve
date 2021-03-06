package nerve.network.pocbft.tx.v1;

import io.nuls.base.data.BlockHeader;
import io.nuls.base.data.NulsHash;
import io.nuls.base.data.Transaction;
import io.nuls.base.protocol.TransactionProcessor;
import io.nuls.core.basic.Result;
import io.nuls.core.constant.TxType;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.exception.NulsException;
import nerve.network.pocbft.constant.ConsensusErrorCode;
import nerve.network.pocbft.model.bo.Chain;
import nerve.network.pocbft.model.bo.tx.txdata.CancelDeposit;
import nerve.network.pocbft.model.bo.tx.txdata.Deposit;
import nerve.network.pocbft.utils.LoggerUtil;
import nerve.network.pocbft.utils.manager.ChainManager;
import nerve.network.pocbft.utils.manager.DepositManager;
import nerve.network.pocbft.utils.validator.WithdrawValidator;

import java.io.IOException;
import java.util.*;

/**
 * 脱出共识交易处理器
 *
 * @author: Jason
 * @date 2019/6/1
 */
@Component("WithdrawProcessorV1")
public class WithdrawProcessor implements TransactionProcessor {
    @Autowired
    private DepositManager depositManager;
    @Autowired
    private ChainManager chainManager;
    @Autowired
    private WithdrawValidator validator;

    @Override
    public int getType() {
        return TxType.CANCEL_DEPOSIT;
    }

    @Override
    public Map<String, Object> validate(int chainId, List<Transaction> txs, Map<Integer, List<Transaction>> txMap, BlockHeader blockHeader) {
        Chain chain = chainManager.getChainMap().get(chainId);
        Map<String, Object> result = new HashMap<>(2);
        if (chain == null) {
            LoggerUtil.commonLog.error("Chains do not exist.");
            result.put("txList", txs);
            result.put("errorCode", ConsensusErrorCode.CHAIN_NOT_EXIST.getCode());
            return result;
        }
        List<Transaction> invalidTxList = new ArrayList<>();
        String errorCode = null;
        Set<NulsHash> hashSet = new HashSet<>();
        Result rs;
        for (Transaction withdrawTx : txs) {
            try {
                rs = validator.validate(chain, withdrawTx);
                if (rs.isFailed()) {
                    invalidTxList.add(withdrawTx);
                    chain.getLogger().error("Intelligent contract withdrawal delegation transaction verification failed");
                    errorCode = rs.getErrorCode().getCode();
                    continue;
                }
                CancelDeposit cancelDeposit = new CancelDeposit();
                cancelDeposit.parse(withdrawTx.getTxData(), 0);
                /*
                 * 重复退出节点
                 * */
                if (!hashSet.add(cancelDeposit.getJoinTxHash())) {
                    invalidTxList.add(withdrawTx);
                    chain.getLogger().info("Repeated transactions");
                    errorCode = ConsensusErrorCode.CONFLICT_ERROR.getCode();
                }
            } catch (NulsException e) {
                invalidTxList.add(withdrawTx);
                chain.getLogger().error("Conflict calibration error");
                chain.getLogger().error(e);
                errorCode = e.getErrorCode().getCode();
            } catch (IOException io) {
                invalidTxList.add(withdrawTx);
                chain.getLogger().error("Conflict calibration error");
                chain.getLogger().error(io);
                errorCode = ConsensusErrorCode.SERIALIZE_ERROR.getCode();
            }
        }
        result.put("txList", invalidTxList);
        result.put("errorCode", errorCode);
        return result;
    }

    @Override
    public boolean commit(int chainId, List<Transaction> txs, BlockHeader blockHeader, int syncStatus) {
        Chain chain = chainManager.getChainMap().get(chainId);
        if (chain == null) {
            LoggerUtil.commonLog.error("Chains do not exist.");
            return false;
        }
        List<Transaction> commitSuccessList = new ArrayList<>();
        boolean commitResult = true;
        for (Transaction tx : txs) {
            if (withdrawCommit(tx, blockHeader, chain)) {
                commitSuccessList.add(tx);
            } else {
                commitResult = false;
                break;
            }
        }
        //回滚已提交成功的交易
        if (!commitResult) {
            for (Transaction rollbackTx : commitSuccessList) {
                withdrawRollBack(rollbackTx, chain, blockHeader);
            }
        }
        return commitResult;
    }

    @Override
    public boolean rollback(int chainId, List<Transaction> txs, BlockHeader blockHeader) {
        Chain chain = chainManager.getChainMap().get(chainId);
        if (chain == null) {
            LoggerUtil.commonLog.error("Chains do not exist.");
            return false;
        }
        List<Transaction> rollbackSuccessList = new ArrayList<>();
        boolean rollbackResult = true;
        for (Transaction tx : txs) {
            if (withdrawRollBack(tx, chain, blockHeader)) {
                rollbackSuccessList.add(tx);
            } else {
                rollbackResult = false;
                break;
            }
        }
        //保存已回滚成功的交易
        if (!rollbackResult) {
            for (Transaction commitTx : rollbackSuccessList) {
                withdrawCommit(commitTx, blockHeader, chain);

            }
        }
        return rollbackResult;
    }

    private boolean withdrawCommit(Transaction transaction, BlockHeader header, Chain chain) {
        CancelDeposit cancelDeposit = new CancelDeposit();
        try {
            cancelDeposit.parse(transaction.getTxData(), 0);
        } catch (NulsException e) {
            chain.getLogger().error(e);
            return false;
        }

        //获取该笔交易对应的加入共识委托交易
        Deposit deposit = depositManager.getDeposit(chain, cancelDeposit.getJoinTxHash());
        //委托交易不存在
        if (deposit == null) {
            chain.getLogger().error("The exited deposit information does not exist");
            return false;
        }
        //委托交易已退出
        if (deposit.getDelHeight() > 0) {
            chain.getLogger().error("The exited deposit information has been withdrawn");
            return false;
        }
        //设置退出共识高度
        deposit.setDelHeight(header.getHeight());

        return depositManager.updateDeposit(chain, deposit);
    }

    private boolean withdrawRollBack(Transaction transaction, Chain chain, BlockHeader header) {
        CancelDeposit cancelDeposit = new CancelDeposit();
        try {
            cancelDeposit.parse(transaction.getTxData(), 0);
        } catch (NulsException e) {
            chain.getLogger().error(e);
            return false;
        }
        //获取该笔交易对应的加入共识委托交易
        Deposit deposit = depositManager.getDeposit(chain, cancelDeposit.getJoinTxHash());
        //委托交易不存在
        if (deposit == null) {
            chain.getLogger().error("The deposit information does not exist");
            return false;
        }
        if (deposit.getDelHeight() != header.getHeight()) {
            chain.getLogger().error("Exit delegate height is different from rollback height");
            return false;
        }
        deposit.setDelHeight(-1L);
        return  depositManager.updateDeposit(chain, deposit);
    }
}
