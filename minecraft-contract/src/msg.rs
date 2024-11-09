use cosmwasm_schema::{cw_serde, QueryResponses};

#[cw_serde]
pub struct InstantiateMsg {}

#[cw_serde]
pub enum ExecuteMsg {
    Transfer {
        channel: String,
        wallet: String,
        item: String,
    },
    ClaimItem {
        wallet: String,
    },
}

#[cw_serde]
pub enum IbcExecuteMsg {
    Transfer { wallet: String, item: String },
}

#[cw_serde]
pub struct GetPendingItem {
    pub item: String,
}

#[cw_serde]
#[derive(QueryResponses)]
pub enum QueryMsg {
    #[returns(GetPendingItem)]
    Item { wallet: String },
}
