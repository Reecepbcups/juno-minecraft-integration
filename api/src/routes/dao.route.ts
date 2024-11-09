const express = require('express');
const router = express.Router();
import daoControler from '../controllers/dao.controler';

// Routes for standalone nft contracts (middleware)
router.get('/escrow_account_info', daoControler.getServerEscrowWallet)

// router.post('/make_payment', daoControler.makePaymentToPlayer)

// addBundlePayment, submitBundlePayment
router.post('/make_payment', daoControler.appendBundlePayment)
router.post('/ibc_transfer', daoControler.ibcTransfer)
router.post('/sign_bundle', daoControler.broadcastBundledPayment)
router.get('/pending_payments', daoControler.getPendingPayments)

export default router;
