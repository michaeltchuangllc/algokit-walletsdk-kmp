/*
 * Copyright 2025 Algorand Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import AuthenticationServices
import Foundation
import UIKit

/// View controller for handling Liquid Auth flow on iOS
/// This controller manages the ASAuthorization flow for liquid auth
public class LiquidAuthViewController: UIViewController {
    
    // MARK: - Properties
    
    private var origin: String
    private var requestId: String
    private var algoAddress: String
    private var liquidAuthService: LiquidAuthService?
    
    private var activityIndicator: UIActivityIndicatorView!
    private var statusLabel: UILabel!
    private var onCompletion: (() -> Void)?
    
    // MARK: - Initialization
    
    public init(origin: String, requestId: String, algoAddress: String, onCompletion: (() -> Void)? = nil) {
        self.origin = origin
        self.requestId = requestId
        self.algoAddress = algoAddress
        self.onCompletion = onCompletion
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    // MARK: - Lifecycle
    
    public override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        startLiquidAuth()
    }
    
    // MARK: - UI Setup
    
    private func setupUI() {
        view.backgroundColor = .systemBackground
        
        // Activity indicator
        activityIndicator = UIActivityIndicatorView(style: .large)
        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        activityIndicator.startAnimating()
        view.addSubview(activityIndicator)
        
        // Status label
        statusLabel = UILabel()
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.text = "Connecting to \(origin)..."
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.textColor = .label
        view.addSubview(statusLabel)
        
        // Layout constraints
        NSLayoutConstraint.activate([
            activityIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            activityIndicator.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -30),
            
            statusLabel.topAnchor.constraint(equalTo: activityIndicator.bottomAnchor, constant: 20),
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20)
        ])
    }
    
    // MARK: - Liquid Auth Flow
    
    private func startLiquidAuth() {
        NSLog("🚀 Starting Liquid Auth flow...")
        
        liquidAuthService = LiquidAuthService(
            origin: origin,
            requestId: requestId,
            algoAddress: algoAddress
        )
        
        liquidAuthService?.connect(
            onSuccess: { [weak self] in
                DispatchQueue.main.async {
                    self?.handleSuccess()
                }
            },
            onError: { [weak self] error in
                DispatchQueue.main.async {
                    self?.handleError(error)
                }
            }
        )
    }
    
    private func handleSuccess() {
        NSLog("✅ Liquid Auth completed successfully")
        activityIndicator.stopAnimating()
        statusLabel.text = "Connected successfully!"
        statusLabel.textColor = .systemGreen
        
        // Dismiss after a short delay
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.onCompletion?()
            self?.dismiss(animated: true)
        }
    }
    
    private func handleError(_ error: Error) {
        NSLog("❌ Liquid Auth failed: \(error.localizedDescription)")
        activityIndicator.stopAnimating()
        statusLabel.text = "Connection failed: \(error.localizedDescription)"
        statusLabel.textColor = .systemRed
        
        // Show alert
        let alert = UIAlertController(
            title: "Connection Failed",
            message: error.localizedDescription,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.dismiss(animated: true)
        })
        present(alert, animated: true)
    }
    
    // MARK: - Cleanup
    
    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        liquidAuthService?.disconnect()
    }
}

/// Helper to present LiquidAuthViewController from anywhere
public extension LiquidAuthViewController {
    static func present(
        from parentViewController: UIViewController,
        origin: String,
        requestId: String,
        algoAddress: String,
        onCompletion: (() -> Void)? = nil
    ) {
        let liquidAuthVC = LiquidAuthViewController(
            origin: origin,
            requestId: requestId,
            algoAddress: algoAddress,
            onCompletion: onCompletion
        )
        
        if #available(iOS 13.0, *) {
            liquidAuthVC.modalPresentationStyle = .pageSheet
            if let sheet = liquidAuthVC.sheetPresentationController {
                sheet.detents = [.medium()]
                sheet.prefersGrabberVisible = true
            }
        } else {
            liquidAuthVC.modalPresentationStyle = .formSheet
        }
        
        parentViewController.present(liquidAuthVC, animated: true)
    }
}
