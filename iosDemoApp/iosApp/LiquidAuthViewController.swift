import AuthenticationServices
import Foundation
import UIKit

/// View controller for handling Liquid Auth flow on iOS
public class LiquidAuthViewController: UIViewController {

    // MARK: - Properties

    private var origin: String
    private var requestId: String
    private var algoAddress: String
    private var liquidAuthService: LiquidAuthService?

    // UI
    private var cardView: UIView!
    private var iconView: UIImageView!
    private var titleLabel: UILabel!
    private var statusLabel: UILabel!
    private var activityIndicator: UIActivityIndicatorView!

    private var infoStackView: UIStackView!
    private var requestIdLabel: UILabel!
    private var originLabel: UILabel!
    private var algoAddressLabel: UILabel!

    private var onCompletion: (() -> Void)?

    public var onStreamingConnected: ((_ origin: String,
                                       _ requestId: String,
                                       _ algoAddress: String,
                                       _ service: LiquidAuthService) -> Void)?

    // MARK: - Initialization

    public init(
        origin: String,
        requestId: String,
        algoAddress: String,
        onCompletion: (() -> Void)? = nil
    ) {
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

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        liquidAuthService?.disconnect()
    }

    // MARK: - UI Setup

    private func setupUI() {
        view.backgroundColor = .systemGroupedBackground

        // Card
        cardView = UIView()
        cardView.translatesAutoresizingMaskIntoConstraints = false
        cardView.backgroundColor = .secondarySystemBackground
        cardView.layer.cornerRadius = 20
        cardView.layer.shadowColor = UIColor.black.cgColor
        cardView.layer.shadowOpacity = 0.12
        cardView.layer.shadowOffset = CGSize(width: 0, height: 6)
        cardView.layer.shadowRadius = 14
        view.addSubview(cardView)

        // Icon
        iconView = UIImageView(image: UIImage(systemName: "lock.shield"))
        iconView.translatesAutoresizingMaskIntoConstraints = false
        iconView.tintColor = .systemBlue
        iconView.contentMode = .scaleAspectFit
        cardView.addSubview(iconView)

        // Title
        titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = "Liquid Authentication"
        titleLabel.font = .systemFont(ofSize: 20, weight: .semibold)
        titleLabel.textAlignment = .center
        titleLabel.textColor = .label
        cardView.addSubview(titleLabel)

        // Info labels
        
        originLabel = makeInfoLabel(
            title: "Origin",
            value: origin
        )
        requestIdLabel = makeInfoLabel(
            title: "Request ID",
            value: requestId
        )

        algoAddressLabel = makeInfoLabel(
            title: "Algorand Address",
            value: algoAddress,
            monospace: true
        )

        infoStackView = UIStackView(arrangedSubviews: [
            originLabel,
            requestIdLabel,
            algoAddressLabel
        ])
        infoStackView.translatesAutoresizingMaskIntoConstraints = false
        infoStackView.axis = .vertical
        infoStackView.spacing = 10
        cardView.addSubview(infoStackView)

        // Status
        statusLabel = UILabel()
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.text = "Connecting to \(origin)…"
        statusLabel.font = .systemFont(ofSize: 15)
        statusLabel.textAlignment = .center
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0
        cardView.addSubview(statusLabel)

        // Spinner
        activityIndicator = UIActivityIndicatorView(style: .medium)
        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        activityIndicator.startAnimating()
        cardView.addSubview(activityIndicator)

        NSLayoutConstraint.activate([
            // Card
            cardView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cardView.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            cardView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            cardView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),

            // Icon
            iconView.topAnchor.constraint(equalTo: cardView.topAnchor, constant: 28),
            iconView.centerXAnchor.constraint(equalTo: cardView.centerXAnchor),
            iconView.widthAnchor.constraint(equalToConstant: 36),
            iconView.heightAnchor.constraint(equalToConstant: 36),

            // Title
            titleLabel.topAnchor.constraint(equalTo: iconView.bottomAnchor, constant: 14),
            titleLabel.leadingAnchor.constraint(equalTo: cardView.leadingAnchor, constant: 20),
            titleLabel.trailingAnchor.constraint(equalTo: cardView.trailingAnchor, constant: -20),

            // Info stack
            infoStackView.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 16),
            infoStackView.leadingAnchor.constraint(equalTo: cardView.leadingAnchor, constant: 20),
            infoStackView.trailingAnchor.constraint(equalTo: cardView.trailingAnchor, constant: -20),

            // Status
            statusLabel.topAnchor.constraint(equalTo: infoStackView.bottomAnchor, constant: 16),
            statusLabel.leadingAnchor.constraint(equalTo: cardView.leadingAnchor, constant: 20),
            statusLabel.trailingAnchor.constraint(equalTo: cardView.trailingAnchor, constant: -20),

            // Spinner
            activityIndicator.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 20),
            activityIndicator.centerXAnchor.constraint(equalTo: cardView.centerXAnchor),
            activityIndicator.bottomAnchor.constraint(equalTo: cardView.bottomAnchor, constant: -28)
        ])
    }

    // MARK: - Helpers

    private func makeInfoLabel(
        title: String,
        value: String,
        monospace: Bool = false
    ) -> UILabel {

        let titleAttr = NSAttributedString(
            string: "\(title)\n",
            attributes: [
                .font: UIFont.systemFont(ofSize: 12, weight: .medium),
                .foregroundColor: UIColor.secondaryLabel
            ]
        )

        let valueAttr = NSAttributedString(
            string: value,
            attributes: [
                .font: monospace
                    ? UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
                    : UIFont.systemFont(ofSize: 13),
                .foregroundColor: UIColor.label
            ]
        )

        let combined = NSMutableAttributedString()
        combined.append(titleAttr)
        combined.append(valueAttr)

        let label = UILabel()
        label.numberOfLines = 0
        label.attributedText = combined

        return label
    }

    // MARK: - Liquid Auth Flow

    private func startLiquidAuth() {
        liquidAuthService = LiquidAuthService(
            origin: origin,
            requestId: requestId,
            algoAddress: algoAddress
        )

        liquidAuthService?.connect(
            onSuccess: { [weak self] in
                DispatchQueue.main.async { self?.handleSuccess() }
            },
            onError: { [weak self] error in
                DispatchQueue.main.async { self?.handleError(error) }
            },
            onConnected: { [weak self] in
                DispatchQueue.main.async { self?.handleConnected() }
            }
        )
    }

    private func handleConnected() {
        activityIndicator.stopAnimating()
        iconView.image = UIImage(systemName: "checkmark.seal.fill")
        iconView.tintColor = .systemGreen

        // If an onStreamingConnected handler was provided (streaming viewer flow),
        // show a brief "Starting stream…" message then hand off to the viewer.
        if onStreamingConnected != nil {
            statusLabel.text = "Connected!\n\nStarting video stream…"
            statusLabel.textColor = .systemGreen

            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                guard let self = self else { return }

                let capturedCallback  = self.onStreamingConnected
                let capturedOrigin    = self.origin
                let capturedRequestId = self.requestId
                let capturedAddress   = self.algoAddress

                let service = self.takeLiquidAuthService()

                self.dismiss(animated: true) {
                    guard let service = service else { return }
                    capturedCallback?(capturedOrigin, capturedRequestId, capturedAddress, service)
                }
            }
        } else {
            // Signing/auth-only flow — remain open waiting for transaction requests.
            statusLabel.text = "Connected successfully\n\nWaiting to sign transaction requests…"
            statusLabel.textColor = .systemGreen
        }
    }

    // MARK: - Message Forwarding

    public func setMessageForwardingHandler(_ handler: @escaping (String) -> Void) {
        liquidAuthService?.messageForwardingHandler = handler
    }

    public func takeLiquidAuthService() -> LiquidAuthService? {
        let service = liquidAuthService
        liquidAuthService = nil   // prevents viewWillDisappear from disconnecting
        return service
    }

    private func handleSuccess() {
        activityIndicator.stopAnimating()
        iconView.image = UIImage(systemName: "checkmark.circle.fill")
        iconView.tintColor = .systemGreen

        statusLabel.text = "Session completed successfully"
        statusLabel.textColor = .systemGreen

        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.onCompletion?()
            self?.liquidAuthService?.disconnect()
            self?.dismiss(animated: true)
        }
    }

    private func handleError(_ error: Error) {
        activityIndicator.stopAnimating()
        iconView.image = UIImage(systemName: "xmark.octagon.fill")
        iconView.tintColor = .systemRed

        statusLabel.text = "Connection failed\n\(error.localizedDescription)"
        statusLabel.textColor = .systemRed
    }
}

// MARK: - Presentation Helper

public extension LiquidAuthViewController {
    static func present(
        from parentViewController: UIViewController,
        origin: String,
        requestId: String,
        algoAddress: String,
        onCompletion: (() -> Void)? = nil
    ) {
        let vc = LiquidAuthViewController(
            origin: origin,
            requestId: requestId,
            algoAddress: algoAddress,
            onCompletion: onCompletion
        )

        if #available(iOS 13.0, *) {
            vc.modalPresentationStyle = .pageSheet
            if let sheet = vc.sheetPresentationController {
                sheet.detents = [.medium()]
                sheet.prefersGrabberVisible = true
            }
        } else {
            vc.modalPresentationStyle = .formSheet
        }

        parentViewController.present(vc, animated: true)
    }
}

