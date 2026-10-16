use anchor_lang::prelude::*;
use anchor_spl::{
    associated_token::AssociatedToken,
    token::{self, CloseAccount, Mint, Token, TokenAccount, TransferChecked},
};

declare_id!("EJkkvypbdeRWdFQuf8gm7K65xPsHTUtDsFxsKZceW71B");

pub const WITHDRAW_DELAY_SECONDS: i64 = 888;
pub const TOKEN_DECIMALS: u8 = 6;

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

    pub fn set_authorized_signer(ctx: Context<Manage>, authorized_signer: Pubkey) -> Result<()> {
        ctx.accounts.channel.authorized_signer = authorized_signer;
        Ok(())
    }

    pub fn revoke_authorized_signer(ctx: Context<Manage>) -> Result<()> {
        ctx.accounts.channel.authorized_signer = Pubkey::default();
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

    pub fn close(ctx: Context<Finalize>) -> Result<()> {
        require_keys_eq!(
            ctx.accounts.caller.key(),
            ctx.accounts.channel.payee,
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
    #[msg("Only the channel payee can close the channel")]
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
}