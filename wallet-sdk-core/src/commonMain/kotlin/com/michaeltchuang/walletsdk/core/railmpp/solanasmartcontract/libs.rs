use anchor_lang::prelude::*;
use anchor_spl::{
    associated_token::AssociatedToken,
    token::{self, CloseAccount, Mint, Token, TokenAccount, TransferChecked},
};

declare_id!("EJkkvypbdeRWdFQuf8gm7K65xPsHTUtDsFxsKZceW71B");

pub const WITHDRAW_DELAY_SECONDS: i64 = 888;
pub const TOKEN_DECIMALS: u8 = 6;
pub const PAYEE_SIGNER_PDA_PREFIX: &[u8] = b"payee_signer";

#[cfg(feature = "mainnet")]
pub const USDC_MINT: Pubkey = pubkey!("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
#[cfg(not(feature = "mainnet"))]
pub const USDC_MINT: Pubkey = pubkey!("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU");

#[program]
pub mod escrow_session_vault_solana_manager {
    use super::*;

    pub fn open(
        ctx: Context<Open>,
        salt: [u8; 32],
        deposit_amount: u64,
        authorized_signer: Pubkey,
    ) -> Result<()> {
        require!(deposit_amount > 0, VaultError::InvalidDeposit);
        validate_mint(&ctx.accounts.mint)?;
        let clock = Clock::get()?;
        let channel = &mut ctx.accounts.channel;
        channel.payer = ctx.accounts.payer.key();
        channel.payee = ctx.accounts.payee.key();
        channel.mint = ctx.accounts.mint.key();
        channel.authorized_signer = authorized_signer;
        channel.total_deposit = deposit_amount;
        channel.last_settled = 0;
        channel.latest_voucher_amount = 0;
        channel.start_slot = clock.slot;
        channel.start_timestamp = clock.unix_timestamp;
        channel.close_requested_at = 0;
        channel.bump = ctx.bumps.channel;
        channel.salt = salt;
        channel.payee_signer = Pubkey::default();

        token::transfer_checked(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                TransferChecked {
                    from: ctx.accounts.payer_token.to_account_info(),
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                    authority: ctx.accounts.payer.to_account_info(),
                },
            ),
            deposit_amount,
            TOKEN_DECIMALS,
        )
    }

    pub fn top_up(ctx: Context<TopUp>, amount: u64) -> Result<()> {
        require!(amount > 0, VaultError::InvalidDeposit);
        let total_deposit = ctx
            .accounts
            .channel
            .total_deposit
            .checked_add(amount)
            .ok_or(VaultError::ArithmeticOverflow)?;
        token::transfer_checked(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                TransferChecked {
                    from: ctx.accounts.payer_token.to_account_info(),
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                    authority: ctx.accounts.payer.to_account_info(),
                },
            ),
            amount,
            TOKEN_DECIMALS,
        )?;
        ctx.accounts.channel.total_deposit = total_deposit;
        // As on Algorand, a funded top-up cancels a pending close request.
        ctx.accounts.channel.close_requested_at = 0;
        Ok(())
    }

    /// Payer-controlled: sets the session key allowed to sign settlements.
    pub fn set_authorized_payer_signer(ctx: Context<Manage>, authorized_signer: Pubkey) -> Result<()> {
        ctx.accounts.channel.authorized_signer = authorized_signer;
        Ok(())
    }

    /// Payer-controlled emergency stop: disables settlement until a new payer signer is set.
    pub fn revoke_authorized_payer_signer(ctx: Context<Manage>) -> Result<()> {
        ctx.accounts.channel.authorized_signer = Pubkey::default();
        Ok(())
    }

    /// Payee-controlled, CHANNEL-LEVEL: registers a delegate allowed to call `close` on
    /// the payee's behalf for this channel only (e.g. a hot key when the payee is a Seed
    /// Vault / biometric-gated wallet). Overrides the payee's account-level delegate for
    /// this channel. If neither is set, only the payee itself can close. The delegate
    /// cannot change or revoke itself.
    pub fn set_authorized_payee_signer(ctx: Context<ManagePayee>, payee_signer: Pubkey) -> Result<()> {
        require!(
            payee_signer != Pubkey::default() && payee_signer != ctx.accounts.payee.key(),
            VaultError::InvalidPayeeSigner
        );
        ctx.accounts.channel.payee_signer = payee_signer;
        Ok(())
    }

    /// Payee-controlled: removes the channel-level delegate; the channel falls back to the
    /// payee's account-level delegate (if any), otherwise only the payee can close.
    pub fn revoke_authorized_payee_signer(ctx: Context<ManagePayee>) -> Result<()> {
        ctx.accounts.channel.payee_signer = Pubkey::default();
        Ok(())
    }

    /// Payee-controlled, ACCOUNT-LEVEL: registers a default delegate allowed to call
    /// `close` for every channel (current and future) where the signer is the payee, so
    /// a Seed Vault payee signs once instead of per channel. Stored in a PDA at
    /// [b"payee_signer", payee]; `rent_payer` funds it (often the payee itself) and the
    /// rent is returned to the payee on revoke. Replacing an existing delegate is allowed.
    pub fn set_account_payee_signer(ctx: Context<SetAccountPayeeSigner>, payee_signer: Pubkey) -> Result<()> {
        require!(
            payee_signer != Pubkey::default() && payee_signer != ctx.accounts.payee.key(),
            VaultError::InvalidPayeeSigner
        );
        let config = &mut ctx.accounts.payee_signer_config;
        config.payee = ctx.accounts.payee.key();
        config.signer = payee_signer;
        config.bump = ctx.bumps.payee_signer_config;
        Ok(())
    }

    /// Payee-controlled: removes the account-level delegate and returns its rent to the payee.
    pub fn revoke_account_payee_signer(_ctx: Context<RevokeAccountPayeeSigner>) -> Result<()> {
        Ok(())
    }

    pub fn settle(ctx: Context<Settle>, cumulative_amount: u64) -> Result<()> {
        let channel = &ctx.accounts.channel;
        require!(
            channel.authorized_signer != Pubkey::default()
                && channel.authorized_signer == ctx.accounts.authorized_signer.key(),
            VaultError::UnauthorizedSigner
        );
        require!(
            cumulative_amount > channel.last_settled,
            VaultError::NothingNewToSettle
        );
        require!(
            cumulative_amount <= channel.total_deposit,
            VaultError::VoucherExceedsDeposit
        );
        let delta = cumulative_amount
            .checked_sub(channel.last_settled)
            .ok_or(VaultError::ArithmeticOverflow)?;
        let seeds = channel.signer_seeds();
        let signer_seeds = &[&seeds[..]];
        token::transfer_checked(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.to_account_info(),
                TransferChecked {
                    from: ctx.accounts.vault.to_account_info(),
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.payee_token.to_account_info(),
                    authority: channel.to_account_info(),
                },
                signer_seeds,
            ),
            delta,
            TOKEN_DECIMALS,
        )?;
        ctx.accounts.channel.last_settled = cumulative_amount;
        ctx.accounts.channel.latest_voucher_amount = cumulative_amount;
        Ok(())
    }

    pub fn request_close(ctx: Context<Manage>) -> Result<()> {
        require!(
            ctx.accounts.channel.close_requested_at == 0,
            VaultError::CloseAlreadyRequested
        );
        ctx.accounts.channel.close_requested_at = Clock::get()?.unix_timestamp;
        Ok(())
    }

    /// Payee, or the payee's effective delegate, closes the channel. The effective delegate
    /// is the channel-level `payee_signer` if set, otherwise the payee's account-level
    /// delegate (pass its PDA as `payee_signer_config`; omit it when unused).
    pub fn close(ctx: Context<Finalize>) -> Result<()> {
        let caller = ctx.accounts.caller.key();
        let channel = &ctx.accounts.channel;
        let effective_signer = if channel.payee_signer != Pubkey::default() {
            channel.payee_signer
        } else if let Some(config) = &ctx.accounts.payee_signer_config {
            config.signer
        } else {
            Pubkey::default()
        };
        let is_payee_signer = effective_signer != Pubkey::default() && caller == effective_signer;
        require!(
            caller == channel.payee || is_payee_signer,
            VaultError::UnauthorizedPayee
        );
        refund_and_close_vault(&ctx.accounts)
    }

    pub fn withdraw(ctx: Context<Finalize>) -> Result<()> {
        require_keys_eq!(
            ctx.accounts.caller.key(),
            ctx.accounts.channel.payer,
            VaultError::UnauthorizedPayer
        );
        let requested_at = ctx.accounts.channel.close_requested_at;
        require!(requested_at != 0, VaultError::CloseNotRequested);
        let available_at = requested_at
            .checked_add(WITHDRAW_DELAY_SECONDS)
            .ok_or(VaultError::ArithmeticOverflow)?;
        require!(
            Clock::get()?.unix_timestamp >= available_at,
            VaultError::WithdrawalTooEarly
        );
        refund_and_close_vault(&ctx.accounts)
    }
}

fn validate_mint(mint: &Account<Mint>) -> Result<()> {
    require!(
        mint.decimals == TOKEN_DECIMALS,
        VaultError::InvalidMintDecimals
    );
    // NEVER deploy local-testing to public cluster: it bypasses the USDC allowlist.
    #[cfg(not(feature = "local-testing"))]
    require_keys_eq!(mint.key(), USDC_MINT, VaultError::InvalidMint);
    Ok(())
}

fn refund_and_close_vault<'info>(accounts: &Finalize<'info>) -> Result<()> {
    let seeds = accounts.channel.signer_seeds();
    let signer_seeds = &[&seeds[..]];
    // Refund the actual balance, including tokens transferred outside open/top_up.
    token::transfer_checked(
        CpiContext::new_with_signer(
            accounts.token_program.to_account_info(),
            TransferChecked {
                from: accounts.vault.to_account_info(),
                mint: accounts.mint.to_account_info(),
                to: accounts.payer_token.to_account_info(),
                authority: accounts.channel.to_account_info(),
            },
            signer_seeds,
        ),
        accounts.vault.amount,
        TOKEN_DECIMALS,
    )?;
    token::close_account(CpiContext::new_with_signer(
        accounts.token_program.to_account_info(),
        CloseAccount {
            account: accounts.vault.to_account_info(),
            destination: accounts.payer.to_account_info(),
            authority: accounts.channel.to_account_info(),
        },
        signer_seeds,
    ))
}

#[derive(Accounts)]
#[instruction(salt: [u8; 32])]
pub struct Open<'info> {
    #[account(
        init,
        payer = payer,
        space = 8 + Channel::INIT_SPACE,
        seeds = [b"channel", payer.key().as_ref(), payee.key().as_ref(), mint.key().as_ref(), salt.as_ref()],
        bump
    )]
    pub channel: Account<'info, Channel>,
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: Only the payee public key is stored and used as a PDA seed; no data or owner is trusted.
    pub payee: UncheckedAccount<'info>,
    #[account(constraint = mint.decimals == TOKEN_DECIMALS @ VaultError::InvalidMintDecimals)]
    pub mint: Account<'info, Mint>,
    #[account(mut, token::mint = mint, token::authority = payer)]
    pub payer_token: Account<'info, TokenAccount>,
    #[account(init, payer = payer, associated_token::mint = mint, associated_token::authority = channel)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Manage<'info> {
    #[account(
        mut,
        seeds = [b"channel", channel.payer.as_ref(), channel.payee.as_ref(), channel.mint.as_ref(), channel.salt.as_ref()],
        bump = channel.bump,
        has_one = payer @ VaultError::UnauthorizedPayer
    )]
    pub channel: Account<'info, Channel>,
    pub payer: Signer<'info>,
}

#[derive(Accounts)]
pub struct ManagePayee<'info> {
    #[account(
        mut,
        seeds = [b"channel", channel.payer.as_ref(), channel.payee.as_ref(), channel.mint.as_ref(), channel.salt.as_ref()],
        bump = channel.bump,
        has_one = payee @ VaultError::UnauthorizedPayee
    )]
    pub channel: Account<'info, Channel>,
    pub payee: Signer<'info>,
}

#[derive(Accounts)]
pub struct SetAccountPayeeSigner<'info> {
    #[account(
        init_if_needed,
        payer = rent_payer,
        space = 8 + PayeeSignerConfig::INIT_SPACE,
        seeds = [PAYEE_SIGNER_PDA_PREFIX, payee.key().as_ref()],
        bump
    )]
    pub payee_signer_config: Account<'info, PayeeSignerConfig>,
    pub payee: Signer<'info>,
    #[account(mut)]
    pub rent_payer: Signer<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct RevokeAccountPayeeSigner<'info> {
    #[account(
        mut,
        close = payee,
        seeds = [PAYEE_SIGNER_PDA_PREFIX, payee.key().as_ref()],
        bump = payee_signer_config.bump,
        has_one = payee @ VaultError::UnauthorizedPayee
    )]
    pub payee_signer_config: Account<'info, PayeeSignerConfig>,
    #[account(mut)]
    pub payee: Signer<'info>,
}

#[derive(Accounts)]
pub struct TopUp<'info> {
    #[account(
        mut,
        seeds = [b"channel", channel.payer.as_ref(), channel.payee.as_ref(), channel.mint.as_ref(), channel.salt.as_ref()],
        bump = channel.bump,
        has_one = payer @ VaultError::UnauthorizedPayer,
        has_one = mint
    )]
    pub channel: Account<'info, Channel>,
    pub payer: Signer<'info>,
    #[account(constraint = mint.decimals == TOKEN_DECIMALS @ VaultError::InvalidMintDecimals)]
    pub mint: Account<'info, Mint>,
    #[account(mut, token::mint = mint, token::authority = payer)]
    pub payer_token: Account<'info, TokenAccount>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = channel)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[derive(Accounts)]
pub struct Settle<'info> {
    #[account(
        mut,
        seeds = [b"channel", channel.payer.as_ref(), channel.payee.as_ref(), channel.mint.as_ref(), channel.salt.as_ref()],
        bump = channel.bump,
        has_one = mint
    )]
    pub channel: Account<'info, Channel>,
    pub authorized_signer: Signer<'info>,
    #[account(constraint = mint.decimals == TOKEN_DECIMALS @ VaultError::InvalidMintDecimals)]
    pub mint: Account<'info, Mint>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = channel)]
    pub vault: Account<'info, TokenAccount>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = channel.payee)]
    pub payee_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[derive(Accounts)]
pub struct Finalize<'info> {
    #[account(
        mut,
        close = payer,
        seeds = [b"channel", channel.payer.as_ref(), channel.payee.as_ref(), channel.mint.as_ref(), channel.salt.as_ref()],
        bump = channel.bump,
        has_one = mint
    )]
    pub channel: Account<'info, Channel>,
    pub caller: Signer<'info>,
    #[account(mut, address = channel.payer @ VaultError::UnauthorizedPayer)]
    pub payer: SystemAccount<'info>,
    #[account(constraint = mint.decimals == TOKEN_DECIMALS @ VaultError::InvalidMintDecimals)]
    pub mint: Account<'info, Mint>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = channel)]
    pub vault: Account<'info, TokenAccount>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = payer)]
    pub payer_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    /// Optional: the channel payee's account-level delegate PDA, used by `close` only
    /// when the channel has no channel-level `payee_signer`. Seeds pin it to the payee.
    #[account(
        seeds = [PAYEE_SIGNER_PDA_PREFIX, channel.payee.as_ref()],
        bump = payee_signer_config.bump
    )]
    pub payee_signer_config: Option<Account<'info, PayeeSignerConfig>>,
}

#[account]
#[derive(InitSpace)]
pub struct Channel {
    pub payer: Pubkey,
    pub payee: Pubkey,
    pub mint: Pubkey,
    pub authorized_signer: Pubkey,
    pub total_deposit: u64,
    pub last_settled: u64,
    pub latest_voucher_amount: u64,
    pub start_slot: u64,
    pub start_timestamp: i64,
    pub close_requested_at: i64,
    pub bump: u8,
    pub salt: [u8; 32],
    /// Optional payee-controlled close delegate; `Pubkey::default()` means none.
    pub payee_signer: Pubkey,
}

/// Account-level payee close delegate, PDA at [b"payee_signer", payee].
#[account]
#[derive(InitSpace)]
pub struct PayeeSignerConfig {
    pub payee: Pubkey,
    pub signer: Pubkey,
    pub bump: u8,
}

impl Channel {
    fn signer_seeds(&self) -> [&[u8]; 6] {
        [
            b"channel",
            self.payer.as_ref(),
            self.payee.as_ref(),
            self.mint.as_ref(),
            self.salt.as_ref(),
            std::slice::from_ref(&self.bump),
        ]
    }
}

#[error_code]
pub enum VaultError {
    #[msg("Only the channel payer can perform this action")]
    UnauthorizedPayer,
    #[msg("Only the channel payee (or its authorized payee signer, for close) can perform this action")]
    UnauthorizedPayee,
    #[msg("Settlement requires the nonzero registered authorized signer")]
    UnauthorizedSigner,
    #[msg("Cumulative amount must exceed the last settled amount")]
    NothingNewToSettle,
    #[msg("Cumulative voucher amount exceeds the total deposit")]
    VoucherExceedsDeposit,
    #[msg("Mint is not the configured USDC mint")]
    InvalidMint,
    #[msg("Mint must have six decimals")]
    InvalidMintDecimals,
    #[msg("Checked arithmetic overflow or underflow")]
    ArithmeticOverflow,
    #[msg("Channel closure has already been requested")]
    CloseAlreadyRequested,
    #[msg("Payer must request closure before withdrawing")]
    CloseNotRequested,
    #[msg("The 888-second withdrawal delay has not elapsed")]
    WithdrawalTooEarly,
    #[msg("Deposit must be greater than zero")]
    InvalidDeposit,
    #[msg("Payee signer must be a nonzero public key different from the payee")]
    InvalidPayeeSigner,
}
