use std::collections::BTreeMap;

use cw_storage_plus::Map;

// Pair the wallet address to the name a user provides.
// pub type WalletMapping = BTreeMap<String, String>;

/// create a new empty wallet mapping for a channel.
/// useful if a channel is opened and we have no data yet
// pub fn new_() -> WalletMapping {
// BTreeMap::new()
// }

/// Items that are pending from a user on this network.
pub const PENDING_ITEMS: Map<String, String> = Map::new("items");
