#[cfg(not(feature = "library"))]
use cosmwasm_std::entry_point;
use cosmwasm_std::{Binary, Deps, DepsMut, Env, MessageInfo, Response, StdResult};

use crate::error::ContractError;
use crate::msg::{ExecuteMsg, IbcExecuteMsg, InstantiateMsg, QueryMsg};
use crate::state::PENDING_ITEMS;

use cosmwasm_std::{to_json_binary, IbcMsg, IbcTimeout, StdError};

// version info for migration info
// const CONTRACT_NAME: &str = "crates.io:minecraft-contract";
// const CONTRACT_VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg_attr(not(feature = "library"), entry_point)]
pub fn instantiate(
    _deps: DepsMut,
    _env: Env,
    _info: MessageInfo,
    _msg: InstantiateMsg,
) -> Result<Response, ContractError> {
    Ok(Response::new().add_attribute("method", "instantiate"))
}

#[cfg_attr(not(feature = "library"), entry_point)]
pub fn execute(
    deps: DepsMut,
    env: Env, // removes the underscore _
    _info: MessageInfo,
    msg: ExecuteMsg,
) -> Result<Response, ContractError> {
    match msg {
        // TODO: permission this to the server only
        ExecuteMsg::Transfer {
            channel,
            wallet,
            item,
        } => {
            Ok(Response::new()
                .add_attribute("method", "transfer")
                .add_attribute("channel", channel.clone())
                .add_message(IbcMsg::SendPacket {
                    channel_id: channel,
                    data: to_json_binary(&IbcExecuteMsg::Transfer {
                        wallet, // wallet who gets the item on the other side
                        item,   // base64 encoded item
                    })?,
                    // default timeout of two minutes.
                    timeout: IbcTimeout::with_timestamp(env.block.time.plus_seconds(120)),
                }))
        }
        ExecuteMsg::ClaimItem { wallet } => {
            let items = PENDING_ITEMS.may_load(deps.storage, wallet.clone())?;
            match items {
                Some(_) => {
                    PENDING_ITEMS.remove(deps.storage, wallet);
                }
                None => {
                    return Err(ContractError::Std(StdError::not_found(
                        "No items pending for this wallet",
                    )));
                }
            }

            // api will read this and mint the item to the wallet so long as this is successful.

            Ok(Response::new().add_attribute("method", "claim_item"))
        }
    }
}

#[cfg_attr(not(feature = "library"), entry_point)]
pub fn query(_deps: Deps, _env: Env, msg: QueryMsg) -> StdResult<Binary> {
    match msg {
        QueryMsg::Item { wallet } => {
            let items = PENDING_ITEMS.load(_deps.storage, wallet)?;
            to_json_binary(&items)
        }
    }
}

/// called on IBC packet receive in other chain
pub fn try_set_item(deps: DepsMut, wallet: String, item: String) -> Result<String, StdError> {
    // yea it overrides, not dealing with Vecs / maps for now.
    crate::state::PENDING_ITEMS.save(deps.storage, wallet.clone(), &item.clone())?;
    Ok(item)
}

#[cfg(test)]
mod tests {}
