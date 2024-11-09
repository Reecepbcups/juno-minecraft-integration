// Express
import { Request, Response } from 'express';
import { getServersEscrowAccountInfo, addBundlePayment, signAndBroadcastBundlePayment, getBundledMessages, performItemIBCTransfer } from '../services/dao.service';

// deprecated for bundled payment
// export const makePaymentToPlayer = async (req: Request, res: Response) => {
//     const {secret, wallet, ucraft_amount, description} = req.body;

//     // console.log("makePayment:", secret, wallet, ucraft_amount, description);

//     const response = await makePayment(secret, wallet, ucraft_amount, description);
//     if (response) return res.status(200).json(response);
//     else return res.status(404).json({ message: 'No Real Estate NFTs found for this wallet' });
// };

export const appendBundlePayment = async (req: Request, res: Response) => {
    const {secret, wallet, ucraft_amount} = req.body;
    console.log("addBundlePayment:", secret, wallet, ucraft_amount);

    const response = await addBundlePayment(secret, wallet, ucraft_amount);
    if (response) return res.status(200).json(response);
    else return res.status(404).json({ message: 'No Real Estate NFTs found for this wallet' });
};

export const ibcTransfer = async (req: Request, res: Response) => {
    const {secret, wallet, item} = req.body;
    console.log("ibcTransfer:", secret, wallet, item);

    const response = await performItemIBCTransfer(secret, wallet, item);
    if (response) return res.status(200).json(response);
    else return res.status(404).json({ message: 'No Real Estate NFTs found for this wallet' });
};

export const broadcastBundledPayment = async (req: Request, res: Response) => {
    const {secret} = req.body;
    console.log("broadcastBundledPayment:", secret);

    const response = await signAndBroadcastBundlePayment(secret);
    if (response) return res.status(200).json(response);
    else return res.status(404).json({ message: 'No Real Estate NFTs found for this wallet' });
};

export const getPendingPayments = async (req: Request, res: Response) => {
    const response = getBundledMessages();
    if (response) return res.status(200).json(response);
    else return res.status(404).json({ message: 'Pending payments error' });
};


export const getServerEscrowWallet = async (req: Request, res: Response) => {
    const response = await getServersEscrowAccountInfo();
    if (response) return res.status(200).json(response);
    else return res.status(404).json({ message: 'ERROR: No escrow account found in config...' });
};

export default {
    getServerEscrowWallet,
    // makePaymentToPlayer,
    appendBundlePayment,
    ibcTransfer,
    broadcastBundledPayment,
    getPendingPayments,
};
